package com.github.guoyyit.genesetenrichmentjava.service;

/**
 * Business exception carrying a stable API error code.
 */
public class AnalysisException extends RuntimeException {

    private final String code;

    public AnalysisException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
