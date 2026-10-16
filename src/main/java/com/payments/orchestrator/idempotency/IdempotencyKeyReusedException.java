package com.payments.orchestrator.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 422: the idempotency key was already used with a different request payload or path. */
public class IdempotencyKeyReusedException extends ResponseStatusException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency-Key was already used with a different request payload.");
    }
}
