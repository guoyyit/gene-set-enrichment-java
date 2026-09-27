package com.github.guoyyit.genesetenrichmentjava.tests.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.genesetenrichmentjava.config.EnrichmentProperties;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.CorrectionMethod;
import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;
import com.github.guoyyit.genesetenrichmentjava.model.JobState;
import com.github.guoyyit.genesetenrichmentjava.service.EnrichmentAnalyzer;
import com.github.guoyyit.genesetenrichmentjava.service.InputCleaner;
import com.github.guoyyit.genesetenrichmentjava.service.JobManager;
import com.github.guoyyit.genesetenrichmentjava.store.FileJobStore;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobManagerIntegrationTest {

    private static final Path STORE_DIR =
            Path.of("target/job-manager-test-" + System.nanoTime());

    private static AnalysisRequest validRequest(long timeoutMs) {
        return new AnalysisRequest(
                List.of("G1", "G2", "G3", "G4", "G5"),
                List.of("G1", "G2"),
                List.of(new AnalysisRequest.GeneAnnotation("G1", "C1"),
                        new AnalysisRequest.GeneAnnotation("G2", "C1"),
                        new AnalysisRequest.GeneAnnotation("G3", "C2")),
                new AnalysisParams(CorrectionMethod.BENJAMINI_HOCHBERG, 1,
                        Integer.MAX_VALUE, false, timeoutMs));
    }

    private static AnalysisRequest invalidRequest() {
        return new AnalysisRequest(List.of("G1"), List.of("ZZ"), List.of(), null);
    }

    private static JobManager newManager(Path dir) {
        EnrichmentProperties props = new EnrichmentProperties();
        props.setStoreDir(dir.toString());
        props.setDefaultTimeoutMs(30_000L);
        props.getExecutor().setCoreSize(2);
        props.getExecutor().setMaxSize(2);
        props.getExecutor().setQueueCapacity(4);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        FileJobStore store = new FileJobStore(props, mapper);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCoreSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("test-enrichment-");
        executor.initialize();

        return new JobManager(props, store,
                new EnrichmentAnalyzer(new InputCleaner()), executor);
    }

    private static boolean awaitState(JobManager manager, String jobId,
                                      JobState state, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (manager.get(jobId).state() == state) {
                return true;
            }
            Thread.sleep(5);
        }
        return manager.get(jobId).state() == state;
    }

    @Test
    void completedJobPersistsResult() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("completed"));
        String id = manager.submit(validRequest(30_000L)).jobId();
        assertTrue(awaitState(manager, id, JobState.COMPLETED, 10_000L));
        JobRecord record = manager.get(id);
        assertNotNull(record.result());
        assertNotNull(record.finishedAt());
        assertEquals(CorrectionMethod.BENJAMINI_HOCHBERG,
                record.result().effectiveParams().correctionMethod());
    }

    @Test
    void invalidJobFailsAndLaterJobsStillSucceed() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("failure-isolation"));
        String badId = manager.submit(invalidRequest()).jobId();
        assertTrue(awaitState(manager, badId, JobState.FAILED, 10_000L));
        assertEquals("INVALID_INPUT", manager.get(badId).error().code());

        // A subsequent job on the same manager must be unaffected.
        String goodId = manager.submit(validRequest(30_000L)).jobId();
        assertTrue(awaitState(manager, goodId, JobState.COMPLETED, 10_000L));
    }

    @Test
    void concurrentSubmissionsAllCompleteAndAreDeterministic() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("concurrency"));
        int count = 40;
        List<String> acceptedIds = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.List<String> rejected = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService clients = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            clients.submit(() -> {
                try {
                    start.await();
                    synchronized (acceptedIds) {
                        acceptedIds.add(manager.submit(validRequest(30_000L)).jobId());
                    }
                } catch (com.github.guoyyit.genesetenrichmentjava.service.AnalysisException e) {
                    synchronized (rejected) {
                        rejected.add(e.code());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        clients.shutdown();

        // Every submission is either accepted or explicitly rejected; the
        // number in flight is bounded by pool+queue even if fast jobs free slots.
        assertEquals(count, acceptedIds.size() + rejected.size());
        assertTrue(rejected.stream().allMatch("SERVICE_BUSY"::equals));

        for (String id : acceptedIds) {
            assertTrue(awaitState(manager, id, JobState.COMPLETED, 15_000L),
                    "job " + id + " state=" + manager.get(id).state());
        }

        // Determinism: results of identical accepted requests are equal.
        var reference = manager.get(acceptedIds.get(0)).result().categories();
        for (String id : acceptedIds.subList(1, acceptedIds.size())) {
            assertEquals(reference, manager.get(id).result().categories());
        }

        // Rejected requests left persisted FAILED records, but do not block later work.
        String after = manager.submit(validRequest(30_000L)).jobId();
        assertTrue(awaitState(manager, after, JobState.COMPLETED, 15_000L));
    }

    @Test
    void cancelledJobIsMarkedCancelledAndHasNoResult() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("cancel"));
        AnalysisRequest huge = hugeSlowRequest();
        String id = manager.submit(huge).jobId();
        // Give the worker a moment to start, then cancel.
        Thread.sleep(100);
        JobRecord cancelled = manager.cancel(id);
        assertTrue(cancelled.state() == JobState.CANCELLED
                || manager.get(id).state() == JobState.CANCELLED);
        assertTrue(awaitState(manager, id, JobState.CANCELLED, 10_000L)
                || manager.get(id).state() == JobState.CANCELLED);
        JobRecord finalRecord = manager.get(id);
        assertEquals(JobState.CANCELLED, finalRecord.state());
        assertTrue(finalRecord.result() == null, "cancelled job must not carry a result");

        // Further submissions still work.
        String goodId = manager.submit(validRequest(30_000L)).jobId();
        assertTrue(awaitState(manager, goodId, JobState.COMPLETED, 10_000L));
    }

    @Test
    void timedOutJobFailsWithTimeoutCode() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("timeout"));
        String id = manager.submit(hugeSlowRequest(50L)).jobId();
        boolean finished = awaitState(manager, id, JobState.FAILED, 15_000L);
        assertTrue(finished);
        JobRecord record = manager.get(id);
        assertEquals(JobState.FAILED, record.state());
        assertEquals("JOB_TIMEOUT", record.error().code());
        assertTrue(record.result() == null, "timed out job must not return partial results");
    }

    @Test
    void boundedExecutorRejectsWhenPoolAndQueueAreFull() throws Exception {
        JobManager manager = newManager(STORE_DIR.resolve("bounded"));

        // Custom analyzer that blocks until released, regardless of input.
        EnrichmentProperties props = new EnrichmentProperties();
        props.setStoreDir(STORE_DIR.resolve("bounded").toString());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("blocking-enrichment-");
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        com.github.guoyyit.genesetenrichmentjava.service.EnrichmentAnalyzer blockingAnalyzer =
                new com.github.guoyyit.genesetenrichmentjava.service.EnrichmentAnalyzer(
                        new InputCleaner()) {
                    @Override
                    public com.github.guoyyit.genesetenrichmentjava.model.AnalysisResult analyze(
                            AnalysisRequest request, AnalysisParams effectiveParams,
                            com.github.guoyyit.genesetenrichmentjava.service.CancellationChecker
                                    cancellationChecker) {
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new com.github.guoyyit.genesetenrichmentjava.service
                                    .AnalysisCancelledException("CANCELLED", "interrupted");
                        }
                        return super.analyze(request, effectiveParams, cancellationChecker);
                    }
                };
        JobManager blockingManager = new JobManager(props,
                new FileJobStore(props, new ObjectMapper().registerModule(new JavaTimeModule())),
                blockingAnalyzer, executor);

        for (int i = 0; i < 6; i++) {
            blockingManager.submit(validRequest(30_000L));
        }
        // 7th must be rejected rather than queued/blocking the caller.
        com.github.guoyyit.genesetenrichmentjava.service.AnalysisException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.github.guoyyit.genesetenrichmentjava.service.AnalysisException.class,
                        () -> blockingManager.submit(validRequest(30_000L)));
        assertEquals("SERVICE_BUSY", ex.code());
        release.countDown();
        executor.shutdown();
    }

    private static AnalysisRequest hugeSlowRequest() {
        return hugeSlowRequest(60_000L);
    }

    private static AnalysisRequest hugeSlowRequest(long timeoutMs) {
        List<String> background = new ArrayList<>();
        List<String> query = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            background.add("G" + i);
            if (i % 5 == 0) {
                query.add("G" + i);
            }
        }
        List<AnalysisRequest.GeneAnnotation> annotations = new ArrayList<>();
        int categories = 200_000;
        for (int c = 0; c < categories; c++) {
            annotations.add(new AnalysisRequest.GeneAnnotation(
                    "G" + ((c * 13) % 50_000), "CAT_" + c));
        }
        return new AnalysisRequest(background, query, annotations,
                new AnalysisParams(CorrectionMethod.NONE, 1,
                        Integer.MAX_VALUE, false, timeoutMs));
    }
}
