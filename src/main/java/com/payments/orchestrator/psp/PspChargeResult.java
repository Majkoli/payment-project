package com.payments.orchestrator.psp;

/** PSP acknowledgement of a charge. The final outcome arrives later via webhook. */
public record PspChargeResult(String pspReference) {
}
