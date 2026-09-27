package com.github.guoyyit.genesetenrichmentjava.service;

/**
 * Called by long-running analysis steps; throws
 * {@link AnalysisCancelledException} once the job is cancelled or timed out.
 */
@FunctionalInterface
public interface CancellationChecker {

    void check();
}
