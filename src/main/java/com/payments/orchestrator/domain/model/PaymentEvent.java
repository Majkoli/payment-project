package com.payments.orchestrator.domain.model;

import java.util.Arrays;
import java.util.Optional;

/** Events emitted by the PSP that drive {@link Charge} state transitions. */
public enum PaymentEvent {
    CHARGE_AUTHORIZED("charge.authorized", ChargeStatus.AUTHORIZED),
    CHARGE_CAPTURED("charge.captured", ChargeStatus.CAPTURED),
    CHARGE_FAILED("charge.failed", ChargeStatus.FAILED),
    CHARGE_REFUNDED("charge.refunded", ChargeStatus.REFUNDED);

    private final String wireType;
    private final ChargeStatus targetStatus;

    PaymentEvent(String wireType, ChargeStatus targetStatus) {
        this.wireType = wireType;
        this.targetStatus = targetStatus;
    }

    /** The status a charge reaches when this event is applied. */
    public ChargeStatus targetStatus() {
        return targetStatus;
    }

    /** The event type string used in PSP webhooks, e.g. {@code charge.authorized}. */
    public String wireType() {
        return wireType;
    }

    /** Resolves a webhook {@code eventType}; empty for types we do not model. */
    public static Optional<PaymentEvent> fromWireType(String wireType) {
        return Arrays.stream(values()).filter(e -> e.wireType.equals(wireType)).findFirst();
    }
}
