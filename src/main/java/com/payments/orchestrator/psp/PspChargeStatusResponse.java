package com.payments.orchestrator.psp;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The PSP's view of a charge, used to reconcile charges whose webhook never arrived.
 *
 * @param status PSP status string, e.g. {@code pending}, {@code authorized}, {@code captured},
 *               {@code failed}, {@code refunded}
 */
public record PspChargeStatusResponse(
        @JsonProperty("id") String pspReference,
        String status,
        Long amountMinor,
        String failureReason) {
}
