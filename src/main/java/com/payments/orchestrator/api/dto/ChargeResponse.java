package com.payments.orchestrator.api.dto;

import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;

import java.time.Instant;
import java.util.UUID;

public record ChargeResponse(
        UUID id,
        String accountId,
        Long amountMinor,
        String currency,
        ChargeStatus status,
        String pspReference,
        Instant createdAt) {

    public static ChargeResponse from(Charge charge) {
        return new ChargeResponse(
                charge.getId(),
                charge.getAccountId(),
                charge.getAmountMinor(),
                charge.getCurrency(),
                charge.getStatus(),
                charge.getPspReference(),
                charge.getCreatedAt());
    }
}
