package com.payments.orchestrator.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 409: another request with the same key is still being processed. */
public class IdempotencyConflictException extends ResponseStatusException {

    public IdempotencyConflictException() {
        super(HttpStatus.CONFLICT, "Request currently being processed. Please retry shortly.");
    }
}
