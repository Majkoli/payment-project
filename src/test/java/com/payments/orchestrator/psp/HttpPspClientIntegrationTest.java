package com.payments.orchestrator.psp;

import com.payments.orchestrator.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class HttpPspClientIntegrationTest extends AbstractIntegrationTest {

    @Autowired PspClient pspClient;

    @BeforeEach
    void reset() {
        PSP.resetAll();
    }

    @Test
    void initiateChargePostsToPspAndReturnsReference() {
        UUID chargeId = UUID.randomUUID();
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse()
                .withStatus(201).withHeader("Content-Type", "application/json").withBody("{\"id\":\"psp_abc\"}")));

        PspChargeResponse response = pspClient.initiateCharge(chargeId, 49_900L, "SEK");

        assertThat(response.pspReference()).isEqualTo("psp_abc");
        PSP.verify(postRequestedFor(urlEqualTo("/v1/charges"))
                .withHeader("Idempotency-Key", equalTo(chargeId.toString()))
                .withRequestBody(equalToJson("""
                        {"reference":"%s","amountMinor":49900,"currency":"SEK"}""".formatted(chargeId))));
    }

    @Test
    void initiateChargeMapsClientErrorToRejected() {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse().withStatus(422).withBody("bad")));

        assertThatThrownBy(() -> pspClient.initiateCharge(UUID.randomUUID(), 1L, "SEK"))
                .isInstanceOf(PspRejectedException.class);
    }

    @Test
    void initiateChargeMapsServerErrorAndRateLimitToUnavailable() {
        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> pspClient.initiateCharge(UUID.randomUUID(), 1L, "SEK"))
                .isInstanceOf(PspUnavailableException.class);

        PSP.stubFor(post(urlEqualTo("/v1/charges")).willReturn(aResponse().withStatus(429)));
        assertThatThrownBy(() -> pspClient.initiateCharge(UUID.randomUUID(), 1L, "SEK"))
                .isInstanceOf(PspUnavailableException.class);
    }

    @Test
    void checkChargeStatusReturnsPspView() {
        PSP.stubFor(get(urlEqualTo("/v1/charges/psp_abc")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"psp_abc\",\"status\":\"failed\",\"amountMinor\":49900,\"failureReason\":\"card_declined\"}")));

        PspChargeStatusResponse status = pspClient.checkChargeStatus("psp_abc");

        assertThat(status.pspReference()).isEqualTo("psp_abc");
        assertThat(status.status()).isEqualTo("failed");
        assertThat(status.amountMinor()).isEqualTo(49_900L);
        assertThat(status.failureReason()).isEqualTo("card_declined");
        PSP.verify(getRequestedFor(urlEqualTo("/v1/charges/psp_abc")));
    }

    @Test
    void checkChargeStatusMapsNotFoundAndOutage() {
        PSP.stubFor(get(urlEqualTo("/v1/charges/unknown")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> pspClient.checkChargeStatus("unknown"))
                .isInstanceOf(PspChargeNotFoundException.class);

        PSP.stubFor(get(urlEqualTo("/v1/charges/down")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> pspClient.checkChargeStatus("down"))
                .isInstanceOf(PspUnavailableException.class);
    }
}
