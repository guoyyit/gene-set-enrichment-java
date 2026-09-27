package com.github.guoyyit.genesetenrichmentjava.core;

/**
 * Thrown when an analysis observes cancellation and aborts before producing
 * any (partial) result.
 */
public class AnalysisCancelledException extends RuntimeException {

    public AnalysisCancelledException(String message) {
        super(message);
    }
}
