package com.github.guoyyit.genesetenrichmentjava.tests.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.genesetenrichmentjava.core.AnalysisCancelledException;
import com.github.guoyyit.genesetenrichmentjava.core.EnrichmentService;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.core.model.TaskStatus;
import com.github.guoyyit.genesetenrichmentjava.store.FileResultStore;
import com.github.guoyyit.genesetenrichmentjava.task.TaskManager;
import com.github.guoyyit.genesetenrichmentjava.task.TaskRecord;
import com.github.guoyyit.genesetenrichmentjava.task.TaskRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskManagerTest {

    @TempDir
    Path dir;

    private final List<TaskManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managers.forEach(TaskManager::shutdown);
    }

    private TaskManager manager(EnrichmentService service, int maxConcurrent, int maxQueue) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskManager m = new TaskManager(service, new FileResultStore(dir, mapper), maxConcurrent, maxQueue);
        managers.add(m);
        return m;
    }

    private static AnalysisRequest validRequest(long timeoutMillis) {
        List<String> bg = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            bg.add("g" + i);
        }
        Map<String, List<String>> ann = new LinkedHashMap<>();
        ann.put("catA", bg.subList(0, 10).stream().toList());
        ann.put("catB", bg.subList(20, 30).stream().toList());
        return new AnalysisRequest(bg, bg.subList(0, 5).stream().toList(), ann,
                new AnalysisParameters(CorrectionMethod.BENJAMINI_HOCHBERG, 1, Integer.MAX_VALUE,
                        false, timeoutMillis));
    }

    private static TaskRecord awaitStatus(TaskManager m, String taskId, TaskStatus wanted)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            TaskRecord r = m.find(taskId).orElseThrow();
            if (r.status() == wanted) {
                return r;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("task " + taskId + " did not reach " + wanted
                + ", still " + m.find(taskId).map(TaskRecord::status).orElse(null));
    }

    /** Service that blocks until released, honouring interruption. */
    static class SlowService extends EnrichmentService {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public AnalysisResult analyze(String taskId, AnalysisRequest request) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnalysisCancelledException("cancelled while blocked");
            }
            return super.analyze(taskId, request);
        }
    }

    @Test
    void completedTaskPersistsResult() throws Exception {
        TaskManager m = manager(new EnrichmentService(), 2, 10);
        String id = m.submit(validRequest(0));
        TaskRecord done = awaitStatus(m, id, TaskStatus.COMPLETED);
        assertNotNull(done.result());
        assertEquals(id, done.result().taskId());
        assertEquals(2, done.result().categories().size());
        // persisted to disk as well
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskRecord fromDisk = new FileResultStore(dir, mapper).find(id).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, fromDisk.status());
        assertNotNull(fromDisk.result());
    }

    @Test
    void cancelRunningTaskYieldsNoPartialResult() throws Exception {
        SlowService slow = new SlowService();
        TaskManager m = manager(slow, 1, 10);
        String id = m.submit(validRequest(0));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));

        assertTrue(m.cancel(id));
        TaskRecord cancelled = awaitStatus(m, id, TaskStatus.CANCELLED);
        assertNull(cancelled.result(), "cancelled task must not expose a result");
        assertEquals(TaskManager.ERROR_CANCELLED, cancelled.errorCode());

        slow.release.countDown();
        Thread.sleep(100);
        assertEquals(TaskStatus.CANCELLED, m.find(id).orElseThrow().status(),
                "late completion must not resurrect a cancelled task");
    }

    @Test
    void cancelQueuedTaskPreventsExecution() throws Exception {
        SlowService slow = new SlowService();
        TaskManager m = manager(slow, 1, 10);
        String running = m.submit(validRequest(0));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));
        String queued = m.submit(validRequest(0));

        assertTrue(m.cancel(queued));
        assertEquals(TaskStatus.CANCELLED, m.find(queued).orElseThrow().status());

        slow.release.countDown();
        awaitStatus(m, running, TaskStatus.COMPLETED);
        Thread.sleep(100);
        assertEquals(TaskStatus.CANCELLED, m.find(queued).orElseThrow().status());
    }

    @Test
    void timeoutProducesTimedOutRecordWithoutResult() throws Exception {
        SlowService slow = new SlowService();
        TaskManager m = manager(slow, 1, 10);
        String id = m.submit(validRequest(100));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));

        TaskRecord timedOut = awaitStatus(m, id, TaskStatus.TIMED_OUT);
        assertNull(timedOut.result());
        assertEquals(TaskManager.ERROR_TIMEOUT, timedOut.errorCode());
        slow.release.countDown();
    }

    @Test
    void queueFullRejectsSubmission() throws Exception {
        SlowService slow = new SlowService();
        TaskManager m = manager(slow, 1, 1);
        String first = m.submit(validRequest(0));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));
        m.submit(validRequest(0)); // fills the queue

        assertThrows(TaskRejectedException.class, () -> m.submit(validRequest(0)));
        slow.release.countDown();
        awaitStatus(m, first, TaskStatus.COMPLETED);
    }

    @Test
    void failedTaskDoesNotAffectOthers() throws Exception {
        TaskManager m = manager(new EnrichmentService(), 2, 10);
        String bad = m.submit(new AnalysisRequest(List.of(), List.of("a"),
                Map.of("x", List.of("a")), AnalysisParameters.defaults()));
        String good = m.submit(validRequest(0));

        TaskRecord badDone = awaitStatus(m, bad, TaskStatus.FAILED);
        assertEquals(TaskManager.ERROR_INVALID_REQUEST, badDone.errorCode());
        assertNull(badDone.result());

        TaskRecord goodDone = awaitStatus(m, good, TaskStatus.COMPLETED);
        assertNotNull(goodDone.result());
    }

    @Test
    void restartMarksInFlightTasksFailed() throws Exception {
        SlowService slow = new SlowService();
        TaskManager first = manager(slow, 1, 10);
        String id = first.submit(validRequest(0));
        assertTrue(slow.started.await(5, TimeUnit.SECONDS));
        // simulate crash: stop accepting/processing without persisting a terminal state
        first.shutdown();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TaskManager restarted = manager(new EnrichmentService(), 1, 10);
        TaskRecord recovered = restarted.find(id).orElseThrow();
        assertEquals(TaskStatus.FAILED, recovered.status());
        assertEquals(TaskManager.ERROR_RESTARTED, recovered.errorCode());
        assertNull(recovered.result());
        slow.release.countDown();
    }

    @Test
    void concurrentSubmissionsAreIsolatedAndDeterministic() throws Exception {
        TaskManager m = manager(new EnrichmentService(), 4, 64);
        int n = 24;
        List<String> ids = new ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<String>> submissions = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                submissions.add(pool.submit(() -> m.submit(validRequest(0))));
            }
            for (var f : submissions) {
                ids.add(f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        AnalysisResult reference = null;
        for (String id : ids) {
            TaskRecord done = awaitStatus(m, id, TaskStatus.COMPLETED);
            assertNotNull(done.result());
            if (reference == null) {
                reference = done.result();
            } else {
                // same input -> identical categories and ignored entries, regardless of scheduling
                assertEquals(reference.categories(), done.result().categories());
                assertEquals(reference.ignored(), done.result().ignored());
            }
        }
        assertEquals(n, m.list().size());
    }

    @Test
    void cancelOnTerminalTaskReturnsFalse() throws Exception {
        TaskManager m = manager(new EnrichmentService(), 1, 10);
        String id = m.submit(validRequest(0));
        awaitStatus(m, id, TaskStatus.COMPLETED);
        assertFalse(m.cancel(id));
        assertFalse(m.cancel("does-not-exist"));
    }
}
