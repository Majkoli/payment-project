package com.payments.orchestrator.psp;

/** The PSP could not be reached or answered with a retryable error (5xx, 408, 429, timeout). */
public class PspUnavailableException extends RuntimeException {

    public PspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
