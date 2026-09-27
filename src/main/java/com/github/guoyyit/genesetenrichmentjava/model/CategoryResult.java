package com.github.guoyyit.genesetenrichmentjava.model;

/**
 * Enrichment statistics for a single category.
 */
public record CategoryResult(
        String category,
        int backgroundSize,
        int querySize,
        int categorySize,
        int intersectionSize,
        double foldEnrichment,
        EnrichmentDirection direction,
        double pValue,
        double adjustedPValue) {
}
