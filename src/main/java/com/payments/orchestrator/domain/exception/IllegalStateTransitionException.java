package com.payments.orchestrator.domain.exception;

import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.model.PaymentEvent;
import lombok.Getter;

/** Thrown when a {@link PaymentEvent} cannot be applied to a charge in its current status. */
@Getter
public class IllegalStateTransitionException extends RuntimeException {

    private final ChargeStatus currentStatus;
    private final PaymentEvent event;

    public IllegalStateTransitionException(ChargeStatus currentStatus, PaymentEvent event) {
        super("Cannot apply %s to a charge in status %s".formatted(event, currentStatus));
        this.currentStatus = currentStatus;
        this.event = event;
    }
}
