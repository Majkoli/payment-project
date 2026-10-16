package com.payments.orchestrator.webhook;

import com.payments.orchestrator.AbstractIntegrationTest;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.repository.IdempotencyRecordRepository;
import com.payments.orchestrator.domain.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/webhooks/psp";
    private static final String SECRET = "test_hmac_secret_key_123"; // psp.webhook.secret in application.yml

    @Autowired MockMvc mockMvc;
    @Autowired ChargeRepository chargeRepository;
    @Autowired WebhookEventRepository webhookEventRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    private String pspRef;

    @BeforeEach
    void reset() {
        webhookEventRepository.deleteAll();
        idempotencyRepository.deleteAll();
        chargeRepository.deleteAll();
        pspRef = "psp_" + UUID.randomUUID();
    }

    // ---- authentication ----

    @Test
    void rejectsInvalidSignatureWithoutRecordingAnything() throws Exception {
        saveCharge(ChargeStatus.PENDING);
        String body = event("evt_1", "charge.authorized");

        mockMvc.perform(request(body, "deadbeef")).andExpect(status().isUnauthorized());

        assertThat(webhookEventRepository.count()).isZero();
        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        mockMvc.perform(request(event("evt_1", "charge.authorized"), null)).andExpect(status().isUnauthorized());

        assertThat(webhookEventRepository.count()).isZero();
    }

    @Test
    void rejectsSignatureComputedOverDifferentBody() throws Exception {
        saveCharge(ChargeStatus.PENDING);
        String signedBody = event("evt_1", "charge.authorized");
        String tamperedBody = event("evt_1", "charge.captured");

        mockMvc.perform(request(tamperedBody, sign(signedBody))).andExpect(status().isUnauthorized());

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void acceptsUppercaseAndPrefixedSignatureForms() throws Exception {
        saveCharge(ChargeStatus.PENDING);
        String body = event("evt_1", "charge.authorized");

        mockMvc.perform(request(body, "sha256=" + sign(body).toUpperCase())).andExpect(status().isOk());

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
    }

    // ---- processing ----

    @Test
    void appliesTransitionAndRecordsEvent() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        send(event("evt_1", "charge.authorized")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));
        send(event("evt_2", "charge.captured")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.CAPTURED);
        assertThat(webhookEventRepository.findById("evt_1")).get().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo("charge.authorized");
            assertThat(e.getPayload()).contains("evt_1");
            assertThat(e.getSignature()).hasSize(64);
            assertThat(e.getProcessedAt()).isNotNull();
        });
    }

    @Test
    void duplicateEventIdIsAcknowledgedAndNotReprocessed() throws Exception {
        saveCharge(ChargeStatus.PENDING);
        send(event("evt_1", "charge.authorized")).andExpect(jsonPath("$.status").value("PROCESSED"));
        send(event("evt_2", "charge.captured")).andExpect(jsonPath("$.status").value("PROCESSED"));
        int versionBefore = currentCharge().getVersion();

        send(event("evt_1", "charge.authorized"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));

        assertThat(webhookEventRepository.count()).isEqualTo(2);
        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.CAPTURED);
        assertThat(currentCharge().getVersion()).isEqualTo(versionBefore);
    }

    @Test
    void eventForAlreadyReachedStateIsIdempotentNoOp() throws Exception {
        saveCharge(ChargeStatus.CAPTURED);

        send(event("evt_late", "charge.authorized"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.CAPTURED);
    }

    @Test
    void failedEventStoresFailureReason() throws Exception {
        saveCharge(ChargeStatus.AUTHORIZED);

        send("""
                {"eventId":"evt_f","eventType":"charge.failed","pspReference":"%s","failureReason":"insufficient_funds"}"""
                .formatted(pspRef)).andExpect(status().isOk());

        Charge charge = currentCharge();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(charge.getFailureReason()).isEqualTo("insufficient_funds");
    }

    @Test
    void illegalTransitionFromTerminalStateIsAcknowledgedButIgnored() throws Exception {
        saveCharge(ChargeStatus.FAILED);

        send(event("evt_x", "charge.captured"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(webhookEventRepository.existsById("evt_x")).isTrue();
    }

    @Test
    void amountMismatchIsNotApplied() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        send("""
                {"eventId":"evt_a","eventType":"charge.authorized","pspReference":"%s","amountMinor":1}"""
                .formatted(pspRef)).andExpect(jsonPath("$.status").value("IGNORED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void matchingAmountIsApplied() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        send("""
                {"eventId":"evt_a","eventType":"charge.authorized","pspReference":"%s","amountMinor":49900}"""
                .formatted(pspRef)).andExpect(jsonPath("$.status").value("PROCESSED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
    }

    @Test
    void unknownEventTypeIsAcknowledged() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        send(event("evt_u", "charge.dispute_opened"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void unknownPspReferenceReturns404AndDoesNotConsumeTheEventId() throws Exception {
        // webhook outruns our storing of the PSP reference: PSP must be able to retry the same eventId
        String body = event("evt_early", "charge.authorized");

        send(body).andExpect(status().isNotFound());
        assertThat(webhookEventRepository.count()).isZero();

        saveCharge(ChargeStatus.PENDING);
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));
        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
    }

    @Test
    void malformedButAuthenticBodiesAreBadRequests() throws Exception {
        send("not json").andExpect(status().isBadRequest());
        send("{\"eventType\":\"charge.authorized\",\"pspReference\":\"x\"}").andExpect(status().isBadRequest());
        send("{\"eventId\":\"e\",\"pspReference\":\"x\"}").andExpect(status().isBadRequest());
        send("{\"eventId\":\"e\",\"eventType\":\"charge.authorized\"}").andExpect(status().isBadRequest());

        assertThat(webhookEventRepository.count()).isZero();
    }

    // ---- concurrency ----

    @Test
    void concurrentDeliveriesOfSameEventIdProcessExactlyOnce() throws Exception {
        saveCharge(ChargeStatus.PENDING);
        String body = event("evt_same", "charge.authorized");

        List<String> results = runConcurrently(8, i -> responseStatusField(send(body)));

        assertThat(results).filteredOn("PROCESSED"::equals).hasSize(1);
        assertThat(results).filteredOn("DUPLICATE"::equals).hasSize(7);
        assertThat(webhookEventRepository.count()).isEqualTo(1);
        assertThat(currentCharge().getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
    }

    @Test
    void concurrentDistinctEventsForSameChargeAreSerialized() throws Exception {
        saveCharge(ChargeStatus.PENDING);

        // 8 different event ids all saying "authorized": one applies, the rest are no-ops; no errors
        List<String> results = runConcurrently(8,
                i -> responseStatusField(send(event("evt_auth_" + i, "charge.authorized"))));

        assertThat(results).filteredOn("PROCESSED"::equals).hasSize(1);
        assertThat(results).filteredOn("IGNORED"::equals).hasSize(7);
        assertThat(webhookEventRepository.count()).isEqualTo(8);
        Charge charge = currentCharge();
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.AUTHORIZED);
        assertThat(charge.getVersion()).isEqualTo(1); // updated once, never optimistic-lock-conflicted
    }

    @Test
    void racingFailedAndCapturedNeverProducesAnInconsistentFinalState() throws Exception {
        saveCharge(ChargeStatus.AUTHORIZED);

        runConcurrently(2, i -> responseStatusField(
                i == 0 ? send(event("evt_cap", "charge.captured")) : send(event("evt_fail", "charge.failed"))));

        // Whichever wins first, the other is either rejected (CAPTURED->FAILED) or ignored (FAILED->CAPTURED).
        assertThat(currentCharge().getStatus()).isIn(ChargeStatus.CAPTURED, ChargeStatus.FAILED);
        assertThat(webhookEventRepository.count()).isEqualTo(2);
    }

    // ---- helpers ----

    private Charge saveCharge(ChargeStatus status) {
        return chargeRepository.save(Charge.builder()
                .accountId("acc_1").amountMinor(49_900).status(status).pspReference(pspRef).build());
    }

    private Charge currentCharge() {
        return chargeRepository.findByPspReference(pspRef).orElseThrow();
    }

    private String event(String eventId, String eventType) {
        return """
                {"eventId":"%s","eventType":"%s","pspReference":"%s"}""".formatted(eventId, eventType, pspRef);
    }

    private ResultActions send(String body) throws Exception {
        return mockMvc.perform(request(body, sign(body)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String body, String signature) {
        var builder = MockMvcRequestBuilders.post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        return signature == null ? builder : builder.header("X-Signature-SHA256", signature);
    }

    /** Independent HMAC implementation (not the production verifier) so the test is not circular. */
    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String responseStatusField(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        String content = result.getResponse().getContentAsString();
        return content.replaceAll(".*\"status\":\"([A-Z]+)\".*", "$1");
    }

    private interface IndexedCall<T> {
        T call(int index) throws Exception;
    }

    private static <T> List<T> runConcurrently(int threads, IndexedCall<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return task.call(index);
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
