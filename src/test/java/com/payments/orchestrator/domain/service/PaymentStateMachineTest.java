package com.payments.orchestrator.domain.service;

import com.payments.orchestrator.domain.exception.IllegalStateTransitionException;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.model.PaymentEvent;
import com.payments.orchestrator.domain.service.PaymentStateMachine.TransitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.payments.orchestrator.domain.model.ChargeStatus.*;
import static com.payments.orchestrator.domain.model.PaymentEvent.*;
import static com.payments.orchestrator.domain.service.PaymentStateMachine.TransitionResult.APPLIED;
import static com.payments.orchestrator.domain.service.PaymentStateMachine.TransitionResult.NO_OP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private final PaymentStateMachine stateMachine = new PaymentStateMachine();

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(PENDING, CHARGE_AUTHORIZED, AUTHORIZED),
                Arguments.of(PENDING, CHARGE_FAILED, FAILED),
                Arguments.of(AUTHORIZED, CHARGE_CAPTURED, CAPTURED),
                Arguments.of(AUTHORIZED, CHARGE_FAILED, FAILED),
                Arguments.of(CAPTURED, CHARGE_REFUNDED, REFUNDED));
    }

    static Stream<Arguments> idempotentNoOps() {
        return Stream.of(
                // same-state duplicates
                Arguments.of(AUTHORIZED, CHARGE_AUTHORIZED),
                Arguments.of(CAPTURED, CHARGE_CAPTURED),
                Arguments.of(FAILED, CHARGE_FAILED),
                Arguments.of(REFUNDED, CHARGE_REFUNDED),
                // late / reordered events for states already passed
                Arguments.of(CAPTURED, CHARGE_AUTHORIZED));
    }

    static Stream<Arguments> illegalTransitions() {
        return Stream.of(
                // skipping steps
                Arguments.of(PENDING, CHARGE_CAPTURED),
                Arguments.of(PENDING, CHARGE_REFUNDED),
                Arguments.of(AUTHORIZED, CHARGE_REFUNDED),
                // no failing after capture
                Arguments.of(CAPTURED, CHARGE_FAILED),
                // terminal FAILED
                Arguments.of(FAILED, CHARGE_AUTHORIZED),
                Arguments.of(FAILED, CHARGE_CAPTURED),
                Arguments.of(FAILED, CHARGE_REFUNDED),
                // terminal REFUNDED
                Arguments.of(REFUNDED, CHARGE_AUTHORIZED),
                Arguments.of(REFUNDED, CHARGE_CAPTURED),
                Arguments.of(REFUNDED, CHARGE_FAILED));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("validTransitions")
    void appliesValidTransitions(ChargeStatus from, PaymentEvent event, ChargeStatus expected) {
        Charge charge = chargeIn(from);

        TransitionResult result = stateMachine.apply(charge, event);

        assertThat(result).isEqualTo(APPLIED);
        assertThat(charge.getStatus()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} + {1} is a no-op")
    @MethodSource("idempotentNoOps")
    void treatsAlreadyReachedStatesAsNoOp(ChargeStatus from, PaymentEvent event) {
        Charge charge = chargeIn(from);

        TransitionResult result = stateMachine.apply(charge, event);

        assertThat(result).isEqualTo(NO_OP);
        assertThat(charge.getStatus()).isEqualTo(from);
    }

    @ParameterizedTest(name = "{0} + {1} is rejected")
    @MethodSource("illegalTransitions")
    void rejectsIllegalTransitions(ChargeStatus from, PaymentEvent event) {
        Charge charge = chargeIn(from);

        assertThatThrownBy(() -> stateMachine.apply(charge, event))
                .isInstanceOfSatisfying(IllegalStateTransitionException.class, e -> {
                    assertThat(e.getCurrentStatus()).isEqualTo(from);
                    assertThat(e.getEvent()).isEqualTo(event);
                });
        assertThat(charge.getStatus()).isEqualTo(from);
    }

    @Test
    void chargeDefaultsToPendingSekAndNewVersion() {
        Charge charge = Charge.builder().accountId("acc_1").amountMinor(49_900).build();

        assertThat(charge.getStatus()).isEqualTo(PENDING);
        assertThat(charge.getCurrency()).isEqualTo("SEK");
        assertThat(charge.getId()).isNotNull();
        assertThat(charge.getVersion()).isNull();
    }

    @Test
    void canTransitionToMatchesTransitionTable() {
        for (ChargeStatus from : ChargeStatus.values()) {
            Charge charge = chargeIn(from);
            for (ChargeStatus to : ChargeStatus.values()) {
                assertThat(charge.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(from.allowedTargets().contains(to));
            }
        }
        assertThat(chargeIn(PENDING).canTransitionTo(null)).isFalse();
    }

    private static Charge chargeIn(ChargeStatus status) {
        return Charge.builder().accountId("acc_1").amountMinor(49_900).status(status).build();
    }
}
