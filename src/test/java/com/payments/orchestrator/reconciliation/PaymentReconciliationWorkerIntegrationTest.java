package com.payments.orchestrator.reconciliation;

import com.payments.orchestrator.AbstractIntegrationTest;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.repository.IdempotencyRecordRepository;
import com.payments.orchestrator.domain.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.payments.orchestrator.reconciliation.ReconciliationOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentReconciliationWorkerIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentReconciliationWorker worker;
    @Autowired ChargeRepository chargeRepository;
    @Autowired WebhookEventRepository webhookEventRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        webhookEventRepository.deleteAll();
        idempotencyRepository.deleteAll();
        chargeRepository.deleteAll();
        PSP.resetAll();
    }

    @Test
    void chargeWithoutPspReferenceIsFailedAsExpiredBeforeDispatch() {
        Charge charge = staleCharge(null);

        ReconciliationSummary summary = worker.reconcile();

        Charge reloaded = reload(charge);
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("EXPIRED_BEFORE_GATEWAY_DISPATCH");
        assertThat(summary.count(FAILED_NO_GATEWAY_REFERENCE)).isEqualTo(1);
        PSP.verify(0, getRequestedFor(urlEqualTo("/v1/charges/anything")));
        assertThat(PSP.getAllServeEvents()).isEmpty();
    }

    @Test
    void pspSuccessMovesChargeThroughAuthorizedToCaptured() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "SUCCESS", 49_900L, null);

        ReconciliationSummary summary = worker.reconcile();

        Charge reloaded = reload(charge);
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.CAPTURED);
        assertThat(reloaded.getVersion()).isEqualTo(1); // both hops in one update
        assertThat(summary.count(CAPTURED)).isEqualTo(1);
    }

    @Test
    void pspFailedMarksChargeFailedWithPspReason() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "FAILED", null, "card_declined");

        ReconciliationSummary summary = worker.reconcile();

        Charge reloaded = reload(charge);
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("card_declined");
        assertThat(summary.count(FAILED_PSP_REPORTED)).isEqualTo(1);
    }

    @Test
    void pspFailedWithoutReasonUsesDefaultReason() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "failed", null, null);

        worker.reconcile();

        assertThat(reload(charge).getFailureReason()).isEqualTo("PSP_REPORTED_FAILED");
    }

    @Test
    void pspNotFoundMarksChargeFailedAsTransactionNotFound() {
        Charge charge = staleCharge(ref());
        PSP.stubFor(get(urlEqualTo("/v1/charges/" + charge.getPspReference())).willReturn(aResponse().withStatus(404)));

        ReconciliationSummary summary = worker.reconcile();

        Charge reloaded = reload(charge);
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("TRANSACTION_NOT_FOUND_ON_PSP");
        assertThat(summary.count(FAILED_NOT_FOUND_ON_PSP)).isEqualTo(1);
    }

    @Test
    void pspAuthorizedOnlyMovesChargeToAuthorized() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "authorized", 49_900L, null);

        ReconciliationSummary summary = worker.reconcile();

        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
        assertThat(summary.count(AUTHORIZED)).isEqualTo(1);
    }

    @Test
    void pspStillPendingLeavesChargeUntouched() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "pending", null, null);

        ReconciliationSummary summary = worker.reconcile();

        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(summary.count(STILL_PENDING)).isEqualTo(1);
    }

    @Test
    void unknownPspStatusIsAnAnomalyAndNotApplied() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "refunded", null, null);

        ReconciliationSummary summary = worker.reconcile();

        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(summary.count(ANOMALY)).isEqualTo(1);
    }

    @Test
    void amountMismatchIsAnAnomalyAndNotApplied() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "SUCCESS", 1L, null);

        ReconciliationSummary summary = worker.reconcile();

        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(summary.count(ANOMALY)).isEqualTo(1);
    }

    @Test
    void recentPendingChargesAndSettledChargesAreNotTouched() {
        Charge recent = chargeRepository.save(Charge.builder()
                .accountId("acc").amountMinor(100).pspReference(ref()).build()); // created just now
        Charge oldButCaptured = staleCharge(ref());
        jdbcTemplate.update("update charges set status = 'CAPTURED' where id = ?", oldButCaptured.getId());

        ReconciliationSummary summary = worker.reconcile();

        assertThat(summary.found()).isZero();
        assertThat(reload(recent).getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(reload(oldButCaptured).getStatus()).isEqualTo(ChargeStatus.CAPTURED);
        assertThat(PSP.getAllServeEvents()).isEmpty();
    }

    @Test
    void oneFailingChargeDoesNotHaltTheBatch() {
        Charge ok1 = staleCharge(ref());
        Charge broken = staleCharge(ref());
        Charge ok2 = staleCharge(ref());
        stubStatus(ok1.getPspReference(), "SUCCESS", null, null);
        stubStatus(ok2.getPspReference(), "FAILED", null, "declined");
        PSP.stubFor(get(urlEqualTo("/v1/charges/" + broken.getPspReference())).willReturn(aResponse().withStatus(503)));

        ReconciliationSummary summary = worker.reconcile();

        assertThat(summary.found()).isEqualTo(3);
        assertThat(summary.count(ERROR)).isEqualTo(1);
        assertThat(reload(ok1).getStatus()).isEqualTo(ChargeStatus.CAPTURED);
        assertThat(reload(ok2).getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(reload(broken).getStatus()).isEqualTo(ChargeStatus.PENDING); // retried next run
    }

    @Test
    void chargeSettledByWebhookWhileQueryingPspIsNotOverwritten() throws Exception {
        Charge charge = staleCharge(ref());
        PSP.stubFor(get(urlEqualTo("/v1/charges/" + charge.getPspReference())).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withFixedDelay(700)
                .withBody("{\"id\":\"%s\",\"status\":\"SUCCESS\"}".formatted(charge.getPspReference()))));

        CompletableFuture<ReconciliationSummary> run = CompletableFuture.supplyAsync(worker::reconcile);
        Thread.sleep(300); // worker is now waiting on the PSP, outside any transaction
        jdbcTemplate.update("update charges set status = 'FAILED', failure_reason = 'from_webhook' where id = ?",
                charge.getId()); // a webhook lands meanwhile
        ReconciliationSummary summary = run.get();

        Charge reloaded = reload(charge);
        assertThat(reloaded.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("from_webhook");
        assertThat(summary.count(SKIPPED_ALREADY_SETTLED)).isEqualTo(1);
    }

    @Test
    void secondRunDoesNothingOnceChargesAreSettled() {
        Charge charge = staleCharge(ref());
        stubStatus(charge.getPspReference(), "SUCCESS", null, null);
        worker.reconcile();

        ReconciliationSummary second = worker.reconcile();

        assertThat(second.found()).isZero();
        assertThat(reload(charge).getStatus()).isEqualTo(ChargeStatus.CAPTURED);
    }

    // ---- helpers ----

    private static String ref() {
        return "psp_" + UUID.randomUUID();
    }

    /** Saves a PENDING charge and backdates created_at beyond the 15 minute threshold. */
    private Charge staleCharge(String pspReference) {
        Charge charge = chargeRepository.save(Charge.builder()
                .accountId("acc_1").amountMinor(49_900).pspReference(pspReference).build());
        jdbcTemplate.update("update charges set created_at = ? where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30), charge.getId());
        return charge;
    }

    private Charge reload(Charge charge) {
        return chargeRepository.findById(charge.getId()).orElseThrow();
    }

    private static void stubStatus(String pspReference, String status, Long amountMinor, String failureReason) {
        StringBuilder body = new StringBuilder("{\"id\":\"").append(pspReference).append("\",\"status\":\"").append(status).append('"');
        if (amountMinor != null) {
            body.append(",\"amountMinor\":").append(amountMinor);
        }
        if (failureReason != null) {
            body.append(",\"failureReason\":\"").append(failureReason).append('"');
        }
        body.append('}');
        PSP.stubFor(get(urlEqualTo("/v1/charges/" + pspReference)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(body.toString())));
    }
}
