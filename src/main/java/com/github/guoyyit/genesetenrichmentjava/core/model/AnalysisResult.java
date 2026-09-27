package com.github.guoyyit.genesetenrichmentjava.core.model;

import java.util.List;

/**
 * Complete, immutable result of one analysis run.
 *
 * @param taskId     id of the task that produced this result
 * @param parameters resolved parameters actually used (for traceability)
 * @param ignored    input entries dropped during cleaning
 * @param categories per-category results, deterministically ordered
 *                   (ascending adjusted p-value, ties broken by category id)
 */
public record AnalysisResult(
        String taskId,
        AnalysisParameters parameters,
        List<IgnoredEntry> ignored,
        List<CategoryResult> categories) {
}
