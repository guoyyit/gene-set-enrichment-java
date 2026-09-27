package com.github.guoyyit.genesetenrichmentjava.service;

import com.github.guoyyit.genesetenrichmentjava.config.EnrichmentProperties;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisParams;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisRequest;
import com.github.guoyyit.genesetenrichmentjava.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.model.JobRecord;
import com.github.guoyyit.genesetenrichmentjava.model.JobState;
import com.github.guoyyit.genesetenrichmentjava.store.JobStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Submits, tracks, cancels and times out analysis jobs on a bounded
 * executor. Each job has its own cancellation token, so temporary state never
 * leaks between requests; slow jobs are bounded by timeout and slow jobs
 * cannot block other requests once rejected (SERVICE_BUSY) or running on a
 * pool thread.
 */
@Component
public class JobManager {

    private final EnrichmentProperties properties;
    private final JobStore jobStore;
    private final EnrichmentAnalyzer analyzer;
    private final ThreadPoolTaskExecutor executor;

    /** Live handles for jobs owned by this process; recreated empty on restart. */
    private final ConcurrentHashMap<String, Handle> handles = new ConcurrentHashMap<>();

    public JobManager(EnrichmentProperties properties,
                      JobStore jobStore,
                      EnrichmentAnalyzer analyzer,
                      @Qualifier("enrichmentExecutor") ThreadPoolTaskExecutor executor) {
        this.properties = properties;
        this.jobStore = jobStore;
        this.analyzer = analyzer;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        jobStore.recoverInterruptedJobs();
    }

    private static final class Handle {
        private final Object lock = new Object();
        private volatile boolean cancelRequested;
        private volatile boolean terminalCommitted;
        private volatile Thread worker;
    }

    /**
     * Validates the request and resolves the effective parameters.
     */
    private AnalysisParams resolveParams(AnalysisRequest request) {
        if (request == null) {
            throw new AnalysisException("INVALID_INPUT", "request body is required");
        }
        AnalysisParams params = (request.params() == null ? AnalysisParams.defaults()
                : request.params()).withDefaults();
        if (params.minCategorySize() < 0 || params.maxCategorySize() < 0
                || params.minCategorySize() > params.maxCategorySize()) {
            throw new AnalysisException("INVALID_PARAMS",
                    "require 0 <= minCategorySize <= maxCategorySize");
        }
        long timeout = params.timeoutMs() == null ? properties.getDefaultTimeoutMs()
                : params.timeoutMs();
        if (timeout <= 0) {
            throw new AnalysisException("INVALID_PARAMS", "timeoutMs must be positive");
        }
        return new AnalysisParams(params.correctionMethod(), params.minCategorySize(),
                params.maxCategorySize(), params.caseSensitive(), timeout);
    }

    public JobRecord submit(AnalysisRequest request) {
        AnalysisParams effective = resolveParams(request);
        int backgroundSize = request.background() == null ? 0 : request.background().size();
        int querySize = request.query() == null ? 0 : request.query().size();
        int annotationCount = request.annotations() == null ? 0 : request.annotations().size();

        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        JobRecord pending = new JobRecord(jobId, JobState.PENDING, effective,
                new JobRecord.RequestSummary(backgroundSize, querySize, annotationCount),
                null, null, now, null, null);
        jobStore.save(pending);

        Handle handle = new Handle();
        handles.put(jobId, handle);
        try {
            executor.execute(() -> runJob(jobId, request, effective, handle));
        } catch (RejectedExecutionException e) {
            handles.remove(jobId);
            JobRecord failed = pending.withError(JobState.FAILED,
                    new JobRecord.JobError("SERVICE_BUSY",
                            "server is busy: the bounded job queue is full"), Instant.now());
            jobStore.save(failed);
            throw new AnalysisException("SERVICE_BUSY",
                    "server is busy: the bounded job queue is full");
        }
        return pending;
    }

    private void runJob(String jobId, AnalysisRequest request,
                        AnalysisParams effective, Handle handle) {
        handle.worker = Thread.currentThread();
        Instant startedAt = Instant.now();
        long deadlineNanos = System.nanoTime() + effective.timeoutMs() * 1_000_000L;
        JobRecord current = requireRecord(jobId).withState(JobState.RUNNING, startedAt, null);
        jobStore.save(current);

        CancellationChecker checker = () -> {
            if (handle.cancelRequested) {
                throw new AnalysisCancelledException("CANCELLED",
                        "job was cancelled by the client");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new AnalysisCancelledException("CANCELLED",
                        "job worker was interrupted");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new AnalysisCancelledException("JOB_TIMEOUT",
                        "analysis exceeded the configured timeout of "
                                + effective.timeoutMs() + " ms");
            }
        };

        try {
            AnalysisResult result = analyzer.analyze(request, effective, checker);
            checker.check();
            commitTerminal(jobId, handle,
                    requireRecord(jobId).withResult(result, Instant.now()));
        } catch (AnalysisCancelledException e) {
            JobState state = "JOB_TIMEOUT".equals(e.code())
                    ? JobState.FAILED : JobState.CANCELLED;
            commitTerminal(jobId, handle, requireRecord(jobId).withError(state,
                    new JobRecord.JobError(e.code(), e.getMessage()), Instant.now()));
        } catch (AnalysisException e) {
            commitTerminal(jobId, handle, requireRecord(jobId).withError(JobState.FAILED,
                    new JobRecord.JobError(e.code(), e.getMessage()), Instant.now()));
        } catch (RuntimeException e) {
            commitTerminal(jobId, handle, requireRecord(jobId).withError(JobState.FAILED,
                    new JobRecord.JobError("INTERNAL_ERROR",
                            e.getClass().getSimpleName() + ": " + e.getMessage()),
                    Instant.now()));
        } finally {
            handles.remove(jobId);
        }
    }

    /**
     * Commits a terminal state, unless an explicit cancel already committed
     * one first. Cancelling never overwrites a finished result and a
     * finishing worker never overwrites a recorded cancellation.
     */
    private void commitTerminal(String jobId, Handle handle, JobRecord terminal) {
        synchronized (handle.lock) {
            if (handle.terminalCommitted) {
                return;
            }
            handle.terminalCommitted = true;
            jobStore.save(terminal);
        }
    }

    private JobRecord requireRecord(String jobId) {
        return jobStore.find(jobId)
                .orElseThrow(() -> new AnalysisException("JOB_NOT_FOUND",
                        "no job with id " + jobId));
    }

    public JobRecord get(String jobId) {
        return jobStore.find(jobId)
                .orElseThrow(() -> new AnalysisException("JOB_NOT_FOUND",
                        "no job with id " + jobId));
    }

    public List<JobRecord> list() {
        return jobStore.findAll();
    }

    public JobRecord cancel(String jobId) {
        JobRecord record = requireRecord(jobId);
        Handle handle = handles.get(jobId);
        if (handle == null) {
            // Not owned here: completed/failed already, or interrupted by restart.
            return record;
        }
        synchronized (handle.lock) {
            if (handle.terminalCommitted) {
                return get(jobId);
            }
            handle.cancelRequested = true;
            Thread worker = handle.worker;
            if (worker != null) {
                worker.interrupt();
            }
            JobRecord latest = get(jobId);
            JobState state = latest.state();
            if (state == JobState.PENDING || state == JobState.RUNNING) {
                JobRecord cancelled = latest.withError(JobState.CANCELLED,
                        new JobRecord.JobError("CANCELLED",
                                "job was cancelled by the client"), Instant.now());
                handle.terminalCommitted = true;
                jobStore.save(cancelled);
                return cancelled;
            }
            return latest;
        }
    }

    /**
     * Runs an analysis on the calling thread (no job is persisted) using the
     * default server timeout.
     */
    public AnalysisResult analyzeSynchronously(AnalysisRequest request) {
        AnalysisParams effective = resolveParams(request);
        long deadlineNanos = System.nanoTime() + effective.timeoutMs() * 1_000_000L;
        CancellationChecker checker = () -> {
            if (Thread.currentThread().isInterrupted()) {
                throw new AnalysisCancelledException("CANCELLED",
                        "request was interrupted");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new AnalysisCancelledException("JOB_TIMEOUT",
                        "analysis exceeded the configured timeout of "
                                + effective.timeoutMs() + " ms");
            }
        };
        return analyzer.analyze(request, effective, checker);
    }
}
