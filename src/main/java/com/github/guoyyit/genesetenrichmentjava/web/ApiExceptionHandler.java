package com.github.guoyyit.genesetenrichmentjava.web;

import com.github.guoyyit.genesetenrichmentjava.service.AnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps business exceptions to stable error codes and HTTP statuses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<ErrorResponse> handleAnalysis(AnalysisException ex) {
        HttpStatus status = switch (ex.code()) {
            case "JOB_NOT_FOUND", "INVALID_JOB_ID" -> HttpStatus.NOT_FOUND;
            case "SERVICE_BUSY" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "INVALID_INPUT", "INVALID_PARAMS" -> HttpStatus.BAD_REQUEST;
            case "JOB_TIMEOUT" -> HttpStatus.REQUEST_TIMEOUT;
            case "CANCELLED" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_INPUT", "malformed JSON request body"));
    }
}
