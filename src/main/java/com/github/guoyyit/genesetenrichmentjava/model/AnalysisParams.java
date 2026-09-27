package com.github.guoyyit.genesetenrichmentjava.model;

/**
 * Parameters controlling an enrichment analysis. All fields are nullable on
 * submission; {@link #withDefaults()} resolves them to the effective values
 * that are actually used and echoed back in results for traceability.
 */
public record AnalysisParams(
        CorrectionMethod correctionMethod,
        Integer minCategorySize,
        Integer maxCategorySize,
        Boolean caseSensitive,
        Long timeoutMs) {

    public static final CorrectionMethod DEFAULT_CORRECTION_METHOD = CorrectionMethod.BENJAMINI_HOCHBERG;
    public static final int DEFAULT_MIN_CATEGORY_SIZE = 1;
    public static final int DEFAULT_MAX_CATEGORY_SIZE = Integer.MAX_VALUE;
    public static final boolean DEFAULT_CASE_SENSITIVE = false;

    public AnalysisParams withDefaults() {
        return new AnalysisParams(
                correctionMethod == null ? DEFAULT_CORRECTION_METHOD : correctionMethod,
                minCategorySize == null ? DEFAULT_MIN_CATEGORY_SIZE : minCategorySize,
                maxCategorySize == null ? DEFAULT_MAX_CATEGORY_SIZE : maxCategorySize,
                caseSensitive == null ? DEFAULT_CASE_SENSITIVE : caseSensitive,
                timeoutMs);
    }

    public static AnalysisParams defaults() {
        return new AnalysisParams(null, null, null, null, null).withDefaults();
    }
}
