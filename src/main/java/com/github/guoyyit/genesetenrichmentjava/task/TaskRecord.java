package com.github.guoyyit.genesetenrichmentjava.task;

import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisParameters;
import com.github.guoyyit.genesetenrichmentjava.core.model.AnalysisResult;
import com.github.guoyyit.genesetenrichmentjava.core.model.TaskStatus;

import java.time.Instant;

/**
 * Persistent, immutable snapshot of one analysis task.
 */
public record TaskRecord(
        String taskId,
        TaskStatus status,
        AnalysisParameters parameters,
        AnalysisResult result,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskRecord pending(String taskId, AnalysisParameters parameters, Instant now) {
        return new TaskRecord(taskId, TaskStatus.PENDING, parameters, null, null, null, now, now);
    }

    public TaskRecord withStatus(TaskStatus newStatus, Instant now) {
        return new TaskRecord(taskId, newStatus, parameters, result, errorCode, errorMessage, createdAt, now);
    }

    public TaskRecord completed(AnalysisResult newResult, Instant now) {
        return new TaskRecord(taskId, TaskStatus.COMPLETED, parameters, newResult, null, null, createdAt, now);
    }

    public TaskRecord failed(TaskStatus newStatus, String code, String message, Instant now) {
        return new TaskRecord(taskId, newStatus, parameters, null, code, message, createdAt, now);
    }
}
