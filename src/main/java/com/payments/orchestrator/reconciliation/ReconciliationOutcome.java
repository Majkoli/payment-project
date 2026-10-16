package com.payments.orchestrator.reconciliation;

/** What reconciliation did with one stale PENDING charge. */
public enum ReconciliationOutcome {
    /** PSP reported success; charge moved PENDING -> AUTHORIZED -> CAPTURED. */
    CAPTURED,
    /** PSP reported authorized only; charge moved to AUTHORIZED. */
    AUTHORIZED,
    /** Charge never reached the PSP; marked FAILED (EXPIRED_BEFORE_GATEWAY_DISPATCH). */
    FAILED_NO_GATEWAY_REFERENCE,
    /** PSP reported failure; marked FAILED. */
    FAILED_PSP_REPORTED,
    /** PSP has no such charge (404); marked FAILED (TRANSACTION_NOT_FOUND_ON_PSP). */
    FAILED_NOT_FOUND_ON_PSP,
    /** PSP still reports the charge as pending; left untouched for the next run. */
    STILL_PENDING,
    /** The charge was settled (e.g. by a webhook) while we were talking to the PSP; left as is. */
    SKIPPED_ALREADY_SETTLED,
    /** PSP returned something we cannot or must not apply (unknown status, amount mismatch). */
    ANOMALY,
    /** Processing this charge failed (e.g. PSP unreachable); it will be retried next run. */
    ERROR
}
