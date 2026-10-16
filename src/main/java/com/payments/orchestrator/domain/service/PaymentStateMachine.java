package com.payments.orchestrator.domain.service;

import com.payments.orchestrator.domain.exception.IllegalStateTransitionException;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.model.PaymentEvent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finite state machine enforcing monotonic {@link Charge} transitions.
 *
 * <p>Evaluation order for {@code event} applied to a charge in status {@code current}:
 * <ol>
 *   <li>target == current: duplicate delivery, {@link TransitionResult#NO_OP}.</li>
 *   <li>current is terminal (FAILED/REFUNDED): {@link IllegalStateTransitionException}.</li>
 *   <li>transition allowed: status changes, {@link TransitionResult#APPLIED}.</li>
 *   <li>target is an earlier step of the success path that was already passed
 *       (e.g. CAPTURED receives CHARGE_AUTHORIZED, a late or reordered webhook):
 *       {@link TransitionResult#NO_OP}.</li>
 *   <li>otherwise (e.g. PENDING -> CAPTURED, CAPTURED -> FAILED): {@link IllegalStateTransitionException}.</li>
 * </ol>
 * The service does not persist; the caller owns the transaction and locking.
 */
@Service
public class PaymentStateMachine {

    public enum TransitionResult {
        /** The charge status was changed. */
        APPLIED,
        /** The event was redundant or already superseded; nothing changed. */
        NO_OP
    }

    /** Order of the success path; FAILED is a branch and is intentionally absent. */
    private static final List<ChargeStatus> SUCCESS_PATH = List.of(
            ChargeStatus.PENDING, ChargeStatus.AUTHORIZED, ChargeStatus.CAPTURED, ChargeStatus.REFUNDED);

    public TransitionResult apply(Charge charge, PaymentEvent event) {
        ChargeStatus current = charge.getStatus();
        ChargeStatus target = event.targetStatus();

        if (current == target) {
            return TransitionResult.NO_OP;
        }
        if (current.isTerminal()) {
            throw new IllegalStateTransitionException(current, event);
        }
        if (charge.canTransitionTo(target)) {
            charge.changeStatus(target);
            return TransitionResult.APPLIED;
        }
        if (isAlreadyPassed(current, target)) {
            return TransitionResult.NO_OP;
        }
        throw new IllegalStateTransitionException(current, event);
    }

    private static boolean isAlreadyPassed(ChargeStatus current, ChargeStatus target) {
        int currentIdx = SUCCESS_PATH.indexOf(current);
        int targetIdx = SUCCESS_PATH.indexOf(target);
        return currentIdx >= 0 && targetIdx >= 0 && currentIdx > targetIdx;
    }
}
