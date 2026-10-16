package com.payments.orchestrator.api;

import com.payments.orchestrator.AbstractIntegrationTest;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.domain.model.IdempotencyStatus;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.repository.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChargeControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/charges";
    private static final String BODY = """
            {"accountId":"acc_1","amountMinor":49900,"currency":"SEK"}""";

    @Autowired MockMvc mockMvc;
    @Autowired ChargeRepository chargeRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void reset() {
        idempotencyRepository.deleteAll();
        chargeRepository.deleteAll();
        PSP.resetAll();
    }

    @Test
    void createsChargeAndCallsPspOnce() throws Exception {
        stubPspAccepts("psp_create_1", 0);

        mockMvc.perform(MockMvcRequestBuilders.post(URL).header("Idempotency-Key", "k-create").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pspReference").value("psp_create_1"))
                .andExpect(jsonPath("$.amountMinor").value(49900))
                .andExpect(jsonPath("$.currency").value("SEK"));

        assertThat(chargeRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.findById("k-create")).get().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(r.getResponseStatus()).isEqualTo(201);
            assertThat(r.getChargeId()).isNotNull();
            assertThat(r.getExpiresAt()).isAfter(r.getCreatedAt().plusSeconds(23 * 3600));
        });
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void replaysCompletedResponseWithoutCallingPspAgain() throws Exception {
        stubPspAccepts("psp_replay_1", 0);

        MvcResult first = perform("k-replay", BODY).andExpect(status().isCreated()).andReturn();
        MvcResult second = perform("k-replay", BODY)
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(chargeRepository.count()).isEqualTo(1);
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void omittedCurrencyIsTreatedAsSameRequestAsExplicitSek() throws Exception {
        stubPspAccepts("psp_currency_1", 0);

        perform("k-currency", """
                {"accountId":"acc_1","amountMinor":100}""").andExpect(status().isCreated());
        perform("k-currency", """
                {"amountMinor":100,"currency":"sek","accountId":"acc_1"}""").andExpect(status().isCreated());

        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void rejectsKeyReuseWithDifferentPayload() throws Exception {
        stubPspAccepts("psp_reuse_1", 0);
        perform("k-reuse", BODY).andExpect(status().isCreated());

        perform("k-reuse", """
                {"accountId":"acc_1","amountMinor":50000,"currency":"SEK"}""")
                .andExpect(status().isUnprocessableEntity());

        assertThat(chargeRepository.count()).isEqualTo(1);
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void requiresIdempotencyKeyHeader() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        assertThat(chargeRepository.count()).isZero();
    }

    @Test
    void invalidBodyIsRejectedAndDoesNotConsumeTheKey() throws Exception {
        perform("k-invalid", """
                {"accountId":"acc_1","amountMinor":0}""").andExpect(status().isBadRequest());
        perform("k-invalid", """
                {"amountMinor":100}""").andExpect(status().isBadRequest());

        assertThat(idempotencyRepository.count()).isZero();
        assertThat(chargeRepository.count()).isZero();
    }

    @Test
    void concurrentRequestsWithSameKeyCreateExactlyOneCharge() throws Exception {
        stubPspAccepts("psp_concurrent_1", 800);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return perform("k-concurrent", BODY).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(chargeRepository.count()).isEqualTo(1);
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void pspRejectionFailsChargeAndIsCachedForReplay() throws Exception {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse().withStatus(402).withBody("card_declined")));

        perform("k-declined", BODY)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("FAILED"));
        perform("k-declined", BODY)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("FAILED"));

        Charge charge = chargeRepository.findAll().get(0);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(charge.getFailureReason()).contains("card_declined");
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
    }

    @Test
    void pspOutageReturns502AndKeyCanBeRetried() throws Exception {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse().withStatus(503)));
        perform("k-outage", BODY).andExpect(status().isBadGateway());

        assertThat(idempotencyRepository.findById("k-outage")).get()
                .extracting(r -> r.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(chargeRepository.findAll()).singleElement()
                .extracting(Charge::getStatus).isEqualTo(ChargeStatus.FAILED);

        PSP.resetAll();
        stubPspAccepts("psp_outage_retry", 0);
        perform("k-outage", BODY)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pspReference").value("psp_outage_retry"));

        assertThat(idempotencyRepository.findById("k-outage")).get()
                .extracting(r -> r.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void sendsChargeIdAsPspIdempotencyKey() throws Exception {
        stubPspAccepts("psp_header_1", 0);

        perform("k-header", BODY).andExpect(status().isCreated());

        UUID chargeId = chargeRepository.findAll().get(0).getId();
        PSP.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withHeader("Idempotency-Key", equalTo(chargeId.toString())));
    }

    @Test
    void getChargeByIdReturns200And404ForUnknown() throws Exception {
        stubPspAccepts("psp_get_1", 0);
        perform("k-get", BODY).andExpect(status().isCreated());
        UUID chargeId = chargeRepository.findAll().get(0).getId();

        mockMvc.perform(MockMvcRequestBuilders.get(URL + "/" + chargeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(chargeId.toString()))
                .andExpect(jsonPath("$.pspReference").value("psp_get_1"))
                .andExpect(jsonPath("$.amountMinor").value(49900))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(MockMvcRequestBuilders.get(URL + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions perform(String key, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(URL).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void stubPspAccepts(String pspReference, int delayMs) {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse()
                .withStatus(201)
                .withFixedDelay(delayMs)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + pspReference + "\"}")));
    }
}
