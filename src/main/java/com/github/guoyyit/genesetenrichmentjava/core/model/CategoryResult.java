package com.github.guoyyit.genesetenrichmentjava.core.model;

/**
 * Enrichment result for one category.
 *
 * @param categoryId       category identifier
 * @param categorySize     number of category genes present in the background
 * @param intersectionSize number of query genes in the category
 * @param querySize        cleaned query size
 * @param backgroundSize   cleaned background size
 * @param foldEnrichment   (intersection/query) / (category/background); +Inf when expected is 0
 * @param pValue           raw hypergeometric p-value for the reported direction
 * @param adjustedPValue   p-value after multiple-testing correction
 * @param direction        over- or under-representation
 */
public record CategoryResult(
        String categoryId,
        int categorySize,
        int intersectionSize,
        int querySize,
        int backgroundSize,
        double foldEnrichment,
        double pValue,
        double adjustedPValue,
        EnrichmentDirection direction) {
}
