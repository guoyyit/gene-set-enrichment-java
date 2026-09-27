package com.github.guoyyit.genesetenrichmentjava.core.model;

/**
 * Fully resolved, immutable analysis parameters. Every field has a concrete
 * value so that results are reproducible and traceable.
 *
 * @param correctionMethod multiple-testing correction strategy
 * @param minCategorySize  categories smaller than this (after intersecting with background) are skipped
 * @param maxCategorySize  categories larger than this are skipped
 * @param caseSensitive    whether gene identifiers are matched case-sensitively
 * @param timeoutMillis    wall-clock budget for the computation; 0 means no limit
 */
public record AnalysisParameters(
        CorrectionMethod correctionMethod,
        int minCategorySize,
        int maxCategorySize,
        boolean caseSensitive,
        long timeoutMillis) {

    public static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    public static AnalysisParameters defaults() {
        return new AnalysisParameters(CorrectionMethod.BENJAMINI_HOCHBERG, 1, Integer.MAX_VALUE, false,
                DEFAULT_TIMEOUT_MILLIS);
    }
}
