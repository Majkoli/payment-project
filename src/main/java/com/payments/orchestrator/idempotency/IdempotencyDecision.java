package com.payments.orchestrator.idempotency;

/** Outcome of {@link IdempotencyManagerService#begin}. */
public sealed interface IdempotencyDecision {

    /** This caller owns the key and must process the request, then finalize or fail the key. */
    record Proceed() implements IdempotencyDecision {
    }

    /** The request already completed; return the stored response without re-processing. */
    record Replay(int httpStatus, String responseBody) implements IdempotencyDecision {
    }
}
