package com.github.guoyyit.genesetenrichmentjava.web;

import java.time.Instant;

/**
 * Uniform error payload returned by the API.
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
