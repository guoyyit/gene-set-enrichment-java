package com.github.guoyyit.genesetenrichmentjava.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Multiple-testing correction strategies.
 */
public enum CorrectionMethod {
    BONFERRONI,
    BENJAMINI_HOCHBERG,
    BENJAMINI_YEKUTIELI;

    @JsonCreator
    public static CorrectionMethod fromString(String value) {
        if (value == null) {
            return BENJAMINI_HOCHBERG;
        }
        String normalized = value.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "BONFERRONI" -> BONFERRONI;
            case "BH", "BENJAMINI_HOCHBERG", "FDR" -> BENJAMINI_HOCHBERG;
            case "BY", "BENJAMINI_YEKUTIELI" -> BENJAMINI_YEKUTIELI;
            default -> throw new IllegalArgumentException("Unknown correction method: " + value);
        };
    }
}
