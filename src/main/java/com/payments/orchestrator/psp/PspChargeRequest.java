package com.payments.orchestrator.psp;

import java.util.UUID;

/** Request sent to the PSP. {@code reference} is our charge id and doubles as the PSP idempotency key. */
public record PspChargeRequest(UUID reference, long amount, String currency) {
}
