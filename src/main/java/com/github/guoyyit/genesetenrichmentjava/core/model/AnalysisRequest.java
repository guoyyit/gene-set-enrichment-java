package com.github.guoyyit.genesetenrichmentjava.core.model;

import java.util.List;
import java.util.Map;

/**
 * Raw analysis request as submitted by the caller (before cleaning).
 *
 * @param background   background gene universe (required, non-empty)
 * @param query        genes under analysis (required, non-empty)
 * @param annotations  category id -> gene list (required, non-empty)
 * @param parameters   optional parameters; null means defaults
 */
public record AnalysisRequest(
        List<String> background,
        List<String> query,
        Map<String, List<String>> annotations,
        AnalysisParameters parameters) {

    public AnalysisParameters effectiveParameters() {
        return parameters == null ? AnalysisParameters.defaults() : parameters;
    }
}
