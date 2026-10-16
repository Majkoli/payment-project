package com.payments.orchestrator.reconciliation;

import com.payments.orchestrator.config.ReconciliationProperties;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.model.PaymentEvent;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.service.PaymentStateMachine;
import com.payments.orchestrator.psp.PspChargeNotFoundException;
import com.payments.orchestrator.psp.PspChargeStatusResponse;
import com.payments.orchestrator.psp.PspClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Reconciles PENDING charges whose webhook never arrived (dropped) or which never reached the PSP
 * (orphaned).
 *
 * <p>Per stale charge:
 * <ol>
 *   <li>The PSP is asked for its view <b>outside any transaction</b>: a slow PSP must not hold a
 *       database connection or a row lock.</li>
 *   <li>The result is applied in an <b>isolated transaction</b> (REQUIRES_NEW) that re-locks the
 *       row ({@code SELECT ... FOR UPDATE}) and re-checks it is still PENDING with the same PSP
 *       reference, so a webhook that settled the charge meanwhile always wins and concurrent
 *       workers cannot double-apply.</li>
 *   <li>Any exception is contained to that charge; the batch continues.</li>
 * </ol>
 * Transitions go through {@link PaymentStateMachine}. The state machine has no direct
 * PENDING -> CAPTURED edge, so a PSP "success" applies CHARGE_AUTHORIZED then CHARGE_CAPTURED in the
 * same transaction.
 *
 * <p>The scheduler runs one invocation at a time per instance, so a long run delays rather than
 * overlaps the next. Across instances the row lock keeps the writes safe.
 */
@Slf4j
@Component
public class PaymentReconciliationWorker {

    static final String REASON_NO_GATEWAY_DISPATCH = "EXPIRED_BEFORE_GATEWAY_DISPATCH";
    static final String REASON_NOT_FOUND_ON_PSP = "TRANSACTION_NOT_FOUND_ON_PSP";
    static final String REASON_PSP_REPORTED_FAILED = "PSP_REPORTED_FAILED";

    private final ChargeRepository chargeRepository;
    private final PspClient pspClient;
    private final PaymentStateMachine stateMachine;
    private final ReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate isolatedTransaction;

    public PaymentReconciliationWorker(ChargeRepository chargeRepository,
                                       PspClient pspClient,
                                       PaymentStateMachine stateMachine,
                                       ReconciliationProperties properties,
                                       Clock clock,
                                       PlatformTransactionManager transactionManager) {
        this.chargeRepository = chargeRepository;
        this.pspClient = pspClient;
        this.stateMachine = stateMachine;
        this.properties = properties;
        this.clock = clock;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(cron = "${reconciliation.cron:0 */5 * * * *}")
    public ReconciliationSummary reconcile() {
        Instant cutoff = clock.instant().minus(properties.staleAfter());
        List<Charge> stale = chargeRepository.findStalePendingCharges(cutoff);
        ReconciliationSummary summary = new ReconciliationSummary(stale.size());
        log.info("reconciliation.started staleCharges={} cutoff={}", stale.size(), cutoff);

        for (Charge charge : stale) {
            ReconciliationOutcome outcome;
            try {
                outcome = reconcileCharge(charge);
            } catch (RuntimeException e) {
                outcome = ReconciliationOutcome.ERROR;
                log.warn("reconciliation.charge_error chargeId={} pspReference={} error={}",
                        charge.getId(), charge.getPspReference(), e.toString(), e);
            }
            summary.record(outcome);
        }

        if (stale.isEmpty()) {
            log.info("reconciliation.finished {}", summary);
        } else if (summary.count(ReconciliationOutcome.ERROR) > 0 || summary.count(ReconciliationOutcome.ANOMALY) > 0) {
            log.warn("reconciliation.finished_with_issues {}", summary);
        } else {
            log.info("reconciliation.finished {}", summary);
        }
        return summary;
    }

    private ReconciliationOutcome reconcileCharge(Charge charge) {
        String pspReference = charge.getPspReference();

        if (pspReference == null || pspReference.isBlank()) {
            return settle(charge.getId(), null, new Settlement(
                    List.of(PaymentEvent.CHARGE_FAILED), REASON_NO_GATEWAY_DISPATCH,
                    ReconciliationOutcome.FAILED_NO_GATEWAY_REFERENCE, null));
        }

        PspChargeStatusResponse pspView;
        try {
            pspView = pspClient.checkChargeStatus(pspReference);
        } catch (PspChargeNotFoundException e) {
            return settle(charge.getId(), pspReference, new Settlement(
                    List.of(PaymentEvent.CHARGE_FAILED), REASON_NOT_FOUND_ON_PSP,
                    ReconciliationOutcome.FAILED_NOT_FOUND_ON_PSP, null));
        }
        // PspUnavailableException / PspRejectedException propagate: counted as ERROR, retried next run.

        String pspStatus = pspView.status() == null ? "" : pspView.status().trim().toUpperCase(Locale.ROOT);
        return switch (pspStatus) {
            case "SUCCESS", "SUCCEEDED", "CAPTURED" -> settle(charge.getId(), pspReference, new Settlement(
                    List.of(PaymentEvent.CHARGE_AUTHORIZED, PaymentEvent.CHARGE_CAPTURED), null,
                    ReconciliationOutcome.CAPTURED, pspView.amountMinor()));
            case "AUTHORIZED" -> settle(charge.getId(), pspReference, new Settlement(
                    List.of(PaymentEvent.CHARGE_AUTHORIZED), null,
                    ReconciliationOutcome.AUTHORIZED, pspView.amountMinor()));
            case "FAILED" -> settle(charge.getId(), pspReference, new Settlement(
                    List.of(PaymentEvent.CHARGE_FAILED),
                    pspView.failureReason() != null && !pspView.failureReason().isBlank()
                            ? pspView.failureReason() : REASON_PSP_REPORTED_FAILED,
                    ReconciliationOutcome.FAILED_PSP_REPORTED, null));
            case "PENDING", "PROCESSING" -> {
                log.info("reconciliation.still_pending chargeId={} pspReference={}", charge.getId(), pspReference);
                yield ReconciliationOutcome.STILL_PENDING;
            }
            default -> {
                log.warn("reconciliation.anomaly chargeId={} pspReference={} reason=unhandled_psp_status pspStatus={}",
                        charge.getId(), pspReference, pspView.status());
                yield ReconciliationOutcome.ANOMALY;
            }
        };
    }

    /** One isolated transaction: lock, re-verify still PENDING, apply via the state machine. */
    private ReconciliationOutcome settle(UUID chargeId, String expectedPspReference, Settlement settlement) {
        return isolatedTransaction.execute(status -> {
            Charge charge = chargeRepository.findByIdForUpdate(chargeId).orElse(null);
            if (charge == null
                    || charge.getStatus() != ChargeStatus.PENDING
                    || !Objects.equals(charge.getPspReference(), expectedPspReference)) {
                log.info("reconciliation.skipped chargeId={} reason=changed_concurrently currentStatus={}",
                        chargeId, charge == null ? "DELETED" : charge.getStatus());
                return ReconciliationOutcome.SKIPPED_ALREADY_SETTLED;
            }
            if (settlement.pspAmountMinor() != null && settlement.pspAmountMinor() != charge.getAmountMinor()) {
                log.warn("reconciliation.anomaly chargeId={} reason=amount_mismatch chargeAmountMinor={} pspAmountMinor={}",
                        chargeId, charge.getAmountMinor(), settlement.pspAmountMinor());
                return ReconciliationOutcome.ANOMALY;
            }

            settlement.events().forEach(event -> stateMachine.apply(charge, event));
            if (settlement.failureReason() != null) {
                charge.setFailureReason(settlement.failureReason());
            }
            log.info("reconciliation.settled chargeId={} pspReference={} outcome={} newStatus={} failureReason={}",
                    chargeId, expectedPspReference, settlement.outcome(), charge.getStatus(), settlement.failureReason());
            if (settlement.outcome() == ReconciliationOutcome.FAILED_NO_GATEWAY_REFERENCE
                    || settlement.outcome() == ReconciliationOutcome.FAILED_NOT_FOUND_ON_PSP) {
                log.warn("reconciliation.orphaned_charge chargeId={} outcome={} createdAt={}",
                        chargeId, settlement.outcome(), charge.getCreatedAt());
            }
            return settlement.outcome();
        });
    }

    /** The events to apply (in order), the failure reason to record, and the outcome to report. */
    private record Settlement(List<PaymentEvent> events, String failureReason,
                              ReconciliationOutcome outcome, Long pspAmountMinor) {
    }
}
