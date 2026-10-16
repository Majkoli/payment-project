package com.payments.orchestrator.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a {@link Charge}. Transitions are strictly monotonic:
 * <pre>
 * PENDING    -> AUTHORIZED | FAILED
 * AUTHORIZED -> CAPTURED   | FAILED
 * CAPTURED   -> REFUNDED
 * FAILED, REFUNDED are terminal.
 * </pre>
 */
public enum ChargeStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    REFUNDED;

    /** Statuses that may directly follow this one. */
    public Set<ChargeStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(AUTHORIZED, FAILED);
            case AUTHORIZED -> EnumSet.of(CAPTURED, FAILED);
            case CAPTURED -> EnumSet.of(REFUNDED);
            case FAILED, REFUNDED -> EnumSet.noneOf(ChargeStatus.class);
        };
    }

    public boolean canTransitionTo(ChargeStatus target) {
        return target != null && allowedTargets().contains(target);
    }

    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
