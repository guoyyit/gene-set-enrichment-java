package com.github.guoyyit.genesetenrichmentjava.model;

import java.time.Instant;

/**
 * Persisted record of an analysis job: state, effective parameters, a summary
 * of the submitted request, and either the result or the failure details.
 */
public record JobRecord(
        String jobId,
        JobState state,
        AnalysisParams params,
        RequestSummary requestSummary,
        AnalysisResult result,
        JobError error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * Size summary of the submitted request, kept for traceability without
     * persisting the full payload.
     */
    public record RequestSummary(int backgroundSize, int querySize, int annotationCount) {
    }

    /**
     * Failure details of a job.
     */
    public record JobError(String code, String message) {
    }

    public JobRecord withState(JobState newState, Instant started, Instant finished) {
        return new JobRecord(jobId, newState, params, requestSummary, result, error,
                createdAt, started, finished);
    }

    public JobRecord withResult(AnalysisResult newResult, Instant finished) {
        return new JobRecord(jobId, JobState.COMPLETED, params, requestSummary, newResult,
                error, createdAt, startedAt, finished);
    }

    public JobRecord withError(JobState newState, JobError newError, Instant finished) {
        return new JobRecord(jobId, newState, params, requestSummary, result, newError,
                createdAt, startedAt, finished);
    }
}
