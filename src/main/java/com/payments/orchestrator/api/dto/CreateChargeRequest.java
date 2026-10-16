package com.payments.orchestrator.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/charges}. {@code currency} defaults to SEK and is
 * upper-cased so that equivalent requests produce the same idempotency fingerprint.
 */
public record CreateChargeRequest(
        @NotBlank @Size(max = 255) String accountId,
        @NotNull @Positive Long amountMinor,
        @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code") String currency) {

    public CreateChargeRequest {
        currency = (currency == null || currency.isBlank()) ? "SEK" : currency.trim().toUpperCase();
    }
}
