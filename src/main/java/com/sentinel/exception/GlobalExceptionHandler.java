package com.sentinel.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(err -> {
            String field = err instanceof FieldError fieldError ? fieldError.getField() : err.getObjectName();
            errors.put(field, err.getDefaultMessage());
        });
        return ResponseEntity.badRequest()
            .body(new ErrorResponse("VALIDATION_FAILED", "Request validation failed", errors));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", ex.getMessage(), null));
    }

    @ExceptionHandler(ScanAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleScanRunning(ScanAlreadyRunningException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("SCAN_IN_PROGRESS", ex.getMessage(), null));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClient(RestClientException ex) {
        log.warn("RestClient error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new ErrorResponse("TARGET_UNREACHABLE",
                "Could not reach target API: " + ex.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
            .body(new ErrorResponse("INTERNAL_ERROR",
                "An unexpected error occurred. Check server logs.", null));
    }

    public record ErrorResponse(
        String code,
        String message,
        Object details,
        LocalDateTime timestamp
    ) {
        public ErrorResponse(String code, String message, Object details) {
            this(code, message, details, LocalDateTime.now());
        }
    }
}
