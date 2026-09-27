package com.github.guoyyit.genesetenrichmentjava.service;

/**
 * Thrown when an analysis is cancelled or exceeds its timeout. No partial
 * result is produced when this is thrown.
 */
public class AnalysisCancelledException extends AnalysisException {

    public AnalysisCancelledException(String code, String message) {
        super(code, message);
    }
}
