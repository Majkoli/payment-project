package com.payments.orchestrator.service;

import com.payments.orchestrator.api.dto.ChargeResponse;

/** Result of creating a charge: the HTTP status and body to return (and to cache for replay). */
public record ChargeOutcome(int httpStatus, ChargeResponse body) {
}
