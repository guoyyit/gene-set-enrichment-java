package com.github.guoyyit.genesetenrichmentjava.api.dto;

import com.github.guoyyit.genesetenrichmentjava.api.ErrorCode;

/**
 * Uniform error body returned by the API.
 */
public record ErrorResponse(ErrorCode code, String message) {
}
