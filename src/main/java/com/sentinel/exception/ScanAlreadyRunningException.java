package com.sentinel.exception;

public class ScanAlreadyRunningException extends RuntimeException {
    public ScanAlreadyRunningException(String targetUrl) {
        super("A scan is already running for: " + targetUrl + ". Wait for it to complete.");
    }
}
