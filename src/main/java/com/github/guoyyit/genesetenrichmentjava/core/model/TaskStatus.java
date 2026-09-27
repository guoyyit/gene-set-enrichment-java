package com.github.guoyyit.genesetenrichmentjava.core.model;

/**
 * Lifecycle states of an analysis task.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
