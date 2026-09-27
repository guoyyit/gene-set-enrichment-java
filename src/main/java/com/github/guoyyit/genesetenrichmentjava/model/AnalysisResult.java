package com.github.guoyyit.genesetenrichmentjava.model;

import java.time.Instant;
import java.util.List;

/**
 * Full result of an enrichment analysis. Categories are sorted by
 * (pValue, category name) so identical input and parameters always yield
 * identical ordering and values.
 */
public record AnalysisResult(
        AnalysisParams effectiveParams,
        IgnoredInputReport ignored,
        int backgroundSize,
        int querySize,
        int categoryCount,
        List<CategoryResult> categories,
        Instant computedAt,
        long durationMs) {
}
