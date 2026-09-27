package com.github.guoyyit.genesetenrichmentjava.task;

/**
 * Thrown when the task queue is at capacity and the submission is rejected.
 */
public class TaskRejectedException extends RuntimeException {

    public TaskRejectedException(String message) {
        super(message);
    }
}
