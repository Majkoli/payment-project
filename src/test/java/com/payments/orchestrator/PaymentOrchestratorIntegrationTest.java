package com.payments.orchestrator;

import com.payments.orchestrator.domain.model.ChargeStatus;
import com.payments.orchestrator.reconciliation.PaymentReconciliationWorker;
import com.payments.orchestrator.reconciliation.ReconciliationOutcome;
import com.payments.orchestrator.reconciliation.ReconciliationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end scenarios over real HTTP (random port, real Tomcat threads, real connection pool),
 * real PostgreSQL (Testcontainers, or the embedded fallback when Docker is absent) and WireMock as
 * the PSP. See {@link AbstractIntegrationTest} for the infrastructure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentOrchestratorIntegrationTest extends AbstractIntegrationTest {

    private static final String WEBHOOK_SECRET = "test_hmac_secret_key_123"; // psp.webhook.secret
    private static final String CHARGE_499_SEK = """
            {"accountId":"acc_499","amountMinor":49900,"currency":"SEK"}""";
    private static final String CHARGE_999_SEK = """
            {"accountId":"acc_499","amountMinor":99900,"currency":"SEK"}""";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentReconciliationWorker reconciliationWorker;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void cleanState() {
        jdbc.update("delete from webhook_events");
        jdbc.update("delete from idempotency_records");
        jdbc.update("delete from charges");
        PSP.resetAll();
    }

    // ---------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------

    @Test
    void infrastructureIsUp() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Integer.class))
                .isPositive();
        assertThat(PSP.isRunning()).isTrue();
        assertThat(PSP.port()).isPositive();
    }

    @Test
    void redisContainerIsReachableWhenRunningOnDocker() {
        assumeTrue(USING_CONTAINERS, "Docker not available: Redis container is only started with Testcontainers");

        redis.opsForValue().set("lock:idempotency:smoke", "1");

        assertThat(redis.opsForValue().get("lock:idempotency:smoke")).isEqualTo("1");
    }

    // ---------------------------------------------------------------------------------------
    // Scenario 1: exact idempotency replay (client retries after a network drop)
    // ---------------------------------------------------------------------------------------

    @Test
    void scenario1_exactReplayReturnsSameResponseAndCallsPspOnce() throws Exception {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"psp_ref_001\"}")));

        HttpResponse<String> first = createCharge("idemp-key-001", CHARGE_499_SEK);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json(first, "status")).isIn("PENDING", "AUTHORIZED");
        assertThat(json(first, "pspReference")).isEqualTo("psp_ref_001");
        assertThat(json(first, "amountMinor")).isEqualTo("49900");
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();

        // the client never saw the response (network drop) and retries immediately
        HttpResponse<String> second = createCharge("idemp-key-001", CHARGE_499_SEK);

        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("Idempotent-Replayed")).contains("true");
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
        assertThat(count("charges")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Scenario 2: same key, altered payload
    // ---------------------------------------------------------------------------------------

    @Test
    void scenario2_reusingKeyWithDifferentAmountIsRejectedWith422() throws Exception {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"psp_ref_002\"}")));

        HttpResponse<String> first = createCharge("idemp-key-002", CHARGE_499_SEK);
        HttpResponse<String> second = createCharge("idemp-key-002", CHARGE_999_SEK);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(422);
        PSP.verify(1, postRequestedFor(urlEqualTo("/v1/charges")));
        assertThat(count("charges")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount_minor from charges", Long.class)).isEqualTo(49_900L);
    }

    // ---------------------------------------------------------------------------------------
    // Scenario 3: 20 identical webhooks racing
    // ---------------------------------------------------------------------------------------

    @Test
    void scenario3_twentyParallelDuplicateWebhooksProcessExactlyOnce() throws Exception {
        seedCharge("psp_ref_100", "PENDING", 49_900, "now()");
        String payload = """
                {"event_id":"evt_dup_01","event_type":"charge.captured","psp_reference":"psp_ref_100","amount_minor":49900}""";
        String signature = hmacSha256Hex(payload);

        int requests = 20;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();
                return postWebhook(payload, signature);
            }));
        }
        startGun.countDown();

        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(responses).filteredOn(r -> r.body().contains("\"PROCESSED\"")).hasSize(1);
        assertThat(responses).filteredOn(r -> r.body().contains("\"DUPLICATE\"")).hasSize(19);
        assertThat(count("webhook_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from charges where psp_reference = 'psp_ref_100'", String.class))
                .isEqualTo(ChargeStatus.CAPTURED.name());
        assertThat(jdbc.queryForObject("select version from charges where psp_reference = 'psp_ref_100'", Integer.class))
                .as("charge was written exactly once").isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Scenario 4: reconciliation of a dropped webhook
    // ---------------------------------------------------------------------------------------

    @Test
    void scenario4_reconciliationCapturesStaleChargeWhosePspReportsSuccess() {
        seedCharge("psp_ref_stale", "PENDING", 49_900, "now() - interval '20 minutes'");
        PSP.stubFor(get(urlEqualTo("/v1/charges/psp_ref_stale")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"psp_ref_stale\",\"status\":\"SUCCESS\"}")));

        ReconciliationSummary summary = reconciliationWorker.reconcile();

        assertThat(jdbc.queryForObject("select status from charges where psp_reference = 'psp_ref_stale'", String.class))
                .isEqualTo(ChargeStatus.CAPTURED.name());
        assertThat(summary.found()).isEqualTo(1);
        assertThat(summary.count(ReconciliationOutcome.CAPTURED)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private HttpResponse<String> createCharge(String idempotencyKey, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/charges"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWebhook(String body, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/webhooks/psp"))
                .header("Content-Type", "application/json")
                .header("X-Signature-SHA256", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Inserts a charge directly; {@code createdAtSql} is a SQL expression such as {@code now() - interval '20 minutes'}. */
    private void seedCharge(String pspReference, String status, long amountMinor, String createdAtSql) {
        jdbc.update("""
                insert into charges (id, account_id, amount_minor, currency, status, psp_reference, version, created_at, updated_at)
                values (?, 'acc_seed', ?, 'SEK', ?, ?, 0, %s, %s)
                """.formatted(createdAtSql, createdAtSql), UUID.randomUUID(), amountMinor, status, pspReference);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Minimal field extraction for flat JSON, to avoid coupling the test to a mapper config. */
    private static String json(HttpResponse<String> response, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(response.body());
        assertThat(m.find()).as("field %s in %s", field, response.body()).isTrue();
        return m.group(1);
    }

    /** Independent HMAC implementation (not the production verifier) so the test is not circular. */
    private static String hmacSha256Hex(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
