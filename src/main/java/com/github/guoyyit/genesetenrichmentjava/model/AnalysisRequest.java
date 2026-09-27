package com.github.guoyyit.genesetenrichmentjava.model;

import java.util.List;

/**
 * Input submitted by a caller: background gene set, query gene set and
 * gene-to-category annotations.
 */
public record AnalysisRequest(
        List<String> background,
        List<String> query,
        List<GeneAnnotation> annotations,
        AnalysisParams params) {

    /**
     * A single gene-to-category annotation entry.
     */
    public record GeneAnnotation(String gene, String category) {
    }
}
