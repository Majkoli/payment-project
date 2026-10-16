package com.payments.orchestrator.psp;

import com.fasterxml.jackson.annotation.JsonProperty;

/** PSP acknowledgement of a charge. The final outcome arrives later via webhook. */
public record PspChargeResponse(@JsonProperty("id") String pspReference) {
}
