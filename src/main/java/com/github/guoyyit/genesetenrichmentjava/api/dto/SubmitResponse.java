package com.github.guoyyit.genesetenrichmentjava.api.dto;

import com.github.guoyyit.genesetenrichmentjava.core.model.TaskStatus;

/**
 * Response returned when a task is accepted.
 */
public record SubmitResponse(String taskId, TaskStatus status) {
}
