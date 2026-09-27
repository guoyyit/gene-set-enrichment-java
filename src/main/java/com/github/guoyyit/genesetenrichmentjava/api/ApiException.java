package com.github.guoyyit.genesetenrichmentjava.api;

import org.springframework.http.HttpStatus;

/**
 * Exception carrying a stable error code and an HTTP status.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;

    public ApiException(ErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
