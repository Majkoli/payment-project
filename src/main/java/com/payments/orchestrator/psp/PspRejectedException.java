package com.payments.orchestrator.psp;

/** The PSP definitively refused the charge (non-retryable 4xx). */
public class PspRejectedException extends RuntimeException {

    public PspRejectedException(String message) {
        super(message);
    }
}
