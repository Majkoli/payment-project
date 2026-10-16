package com.payments.orchestrator.domain.model;

/** Events emitted by the PSP that drive {@link Charge} state transitions. */
public enum PaymentEvent {
    CHARGE_AUTHORIZED(ChargeStatus.AUTHORIZED),
    CHARGE_CAPTURED(ChargeStatus.CAPTURED),
    CHARGE_FAILED(ChargeStatus.FAILED),
    CHARGE_REFUNDED(ChargeStatus.REFUNDED);

    private final ChargeStatus targetStatus;

    PaymentEvent(ChargeStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    /** The status a charge reaches when this event is applied. */
    public ChargeStatus targetStatus() {
        return targetStatus;
    }
}
