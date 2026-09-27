package com.github.guoyyit.genesetenrichmentjava.task;

import com.github.guoyyit.genesetenrichmentjava.core.AnalysisCancelledException;
import com.github.guoyyit.genesetenrichmentjava.core.EnrichmentService;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.TaskStatus;
import com.github.guoyyit.genesetenrichmentjava.store.ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Accepts analysis tasks, executes them on a bounded worker pool with
 * per-task timeout, supports cancellation, and persists every state change.
 *
 * <p>State transitions are guarded so a task in a terminal state
 * (COMPLETED / FAILED / CANCELLED / TIMED_OUT) is never overwritten, which
 * keeps cancellation races from resurrecting a task.
 */
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    public static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";
    public static final String ERROR_QUEUE_FULL = "TASK_QUEUE_FULL";
    public static final String ERROR_RESTARTED = "SERVICE_RESTARTED";
    public static final String ERROR_TIMEOUT = "TASK_TIMEOUT";
    public static final String ERROR_CANCELLED = "TASK_CANCELLED";

    private final EnrichmentService enrichmentService;
    private final ResultStore store;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService watchdog;

    private final Map<String, TaskRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    /** taskId -> true when the in-flight cancellation was caused by a timeout. */
    private final Map<String, Boolean> timeoutCancelled = new ConcurrentHashMap<>();

    public TaskManager(EnrichmentService enrichmentService, ResultStore store,
                       int maxConcurrent, int maxQueue) {
        this.enrichmentService = enrichmentService;
        this.store = store;
        this.workers = new ThreadPoolExecutor(maxConcurrent, maxConcurrent,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(maxQueue),
                runnable -> {
                    Thread t = new Thread(runnable, "enrichment-worker");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "enrichment-watchdog");
            t.setDaemon(true);
            return t;
        });
        recover();
    }

    /**
     * Marks tasks that were in flight when the service last stopped as failed,
     * so they are not reported as running forever after a restart.
     */
    private void recover() {
        for (TaskRecord stored : store.findAll()) {
            TaskRecord effective = stored;
            if (stored.status() == TaskStatus.PENDING || stored.status() == TaskStatus.RUNNING) {
                effective = stored.failed(TaskStatus.FAILED, ERROR_RESTARTED,
                        "service restarted while task was " + stored.status(), Instant.now());
                store.save(effective);
            }
            records.put(effective.taskId(), effective);
        }
    }

    /**
     * Submits a new task. Returns the assigned task id.
     *
 * @throws TaskRejectedException when the queue is full
     */
    public String submit(AnalysisRequest request) {
        String taskId = UUID.randomUUID().toString();
        TaskRecord record = TaskRecord.pending(taskId, request.effectiveParameters(), Instant.now());
        records.put(taskId, record);
        store.save(record);
        try {
            Future<?> future = workers.submit(() -> run(taskId, request));
            futures.put(taskId, future);
        } catch (RuntimeException e) {
            transition(taskId, current -> current.failed(TaskStatus.FAILED, ERROR_QUEUE_FULL,
                    "task queue is full", Instant.now()));
            futures.remove(taskId);
            throw new TaskRejectedException("task queue is full, retry later");
        }
        return taskId;
    }

    private void run(String taskId, AnalysisRequest request) {
        if (!transition(taskId, current -> current.withStatus(TaskStatus.RUNNING, Instant.now()))) {
            return; // cancelled while queued
        }
        long timeoutMillis = request.effectiveParameters().timeoutMillis();
        if (timeoutMillis > 0) {
            scheduleTimeout(taskId, timeoutMillis);
        }
        try {
            AnalysisResult result = enrichmentService.analyze(taskId, request);
            transition(taskId, current -> current.completed(result, Instant.now()));
        } catch (AnalysisCancelledException e) {
            boolean timedOut = timeoutCancelled.getOrDefault(taskId, false);
            transition(taskId, current -> timedOut
                    ? current.failed(TaskStatus.TIMED_OUT, ERROR_TIMEOUT, "analysis exceeded time budget", Instant.now())
                    : current.failed(TaskStatus.CANCELLED, ERROR_CANCELLED, "analysis was cancelled", Instant.now()));
        } catch (IllegalArgumentException e) {
            transition(taskId, current -> current.failed(TaskStatus.FAILED, ERROR_INVALID_REQUEST,
                    e.getMessage(), Instant.now()));
        } catch (Throwable t) {
            log.error("task {} failed unexpectedly", taskId, t);
            transition(taskId, current -> current.failed(TaskStatus.FAILED, ERROR_INTERNAL,
                    "unexpected internal error", Instant.now()));
        } finally {
            futures.remove(taskId);
            timeoutCancelled.remove(taskId);
        }
    }

    private void scheduleTimeout(String taskId, long timeoutMillis) {
        watchdog.schedule(() -> {
            Future<?> future = futures.get(taskId);
            if (future != null && !future.isDone()) {
                timeoutCancelled.put(taskId, true);
                transition(taskId, current -> current.failed(TaskStatus.TIMED_OUT, ERROR_TIMEOUT,
                        "analysis exceeded time budget", Instant.now()));
                future.cancel(true);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Requests cancellation. Returns false when the task is unknown or already terminal.
     */
    public boolean cancel(String taskId) {
        TaskRecord current = records.get(taskId);
        if (current == null || isTerminal(current.status())) {
            return false;
        }
        boolean transitioned = transition(taskId,
                c -> c.failed(TaskStatus.CANCELLED, ERROR_CANCELLED, "cancelled by client", Instant.now()));
        if (transitioned) {
            Future<?> future = futures.get(taskId);
            if (future != null) {
                future.cancel(true);
            }
        }
        return transitioned;
    }

    public Optional<TaskRecord> find(String taskId) {
        return Optional.ofNullable(records.get(taskId));
    }

    public List<TaskRecord> list() {
        return records.values().stream()
                .sorted(Comparator.comparing(TaskRecord::createdAt).thenComparing(TaskRecord::taskId))
                .toList();
    }

    /**
     * Applies {@code transition} iff the task is not in a terminal state.
     * Returns true when the transition was applied.
     */
    private boolean transition(String taskId, java.util.function.UnaryOperator<TaskRecord> transition) {
        boolean[] applied = {false};
        records.computeIfPresent(taskId, (id, current) -> {
            if (isTerminal(current.status())) {
                return current;
            }
            TaskRecord next = transition.apply(current);
            applied[0] = true;
            store.save(next);
            return next;
        });
        return applied[0];
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED || status == TaskStatus.TIMED_OUT;
    }

    public void shutdown() {
        workers.shutdownNow();
        watchdog.shutdownNow();
    }
}
