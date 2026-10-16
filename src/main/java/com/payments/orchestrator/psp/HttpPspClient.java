package com.payments.orchestrator.psp;

import com.payments.orchestrator.config.PspProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * HTTP implementation against the PSP at {@code psp.base-url} (WireMock in local/dev/test).
 * <ul>
 *   <li>{@code POST /v1/charges} answers {@code {"id": "<pspReference>"}}</li>
 *   <li>{@code GET /v1/charges/{pspReference}} answers {@code {"id", "status", "amountMinor", "failureReason"}}</li>
 * </ul>
 */
@Component
public class HttpPspClient implements PspClient {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public HttpPspClient(RestClient.Builder builder, PspProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PspChargeResponse initiateCharge(UUID chargeId, Long amountMinor, String currency) {
        try {
            PspChargeResponse response = restClient.post()
                    .uri("/v1/charges")
                    .header(IDEMPOTENCY_HEADER, chargeId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PspChargeRequest(chargeId, amountMinor, currency))
                    .retrieve()
                    .body(PspChargeResponse.class);
            if (response == null || response.pspReference() == null || response.pspReference().isBlank()) {
                throw new PspUnavailableException("PSP response did not contain a charge id", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            if (isRetryable(e)) {
                throw new PspUnavailableException("PSP asked us to retry (HTTP " + e.getStatusCode().value() + ")", e);
            }
            throw new PspRejectedException("PSP rejected charge (HTTP %d): %s"
                    .formatted(e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString())));
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PspUnavailableException("PSP unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public PspChargeStatusResponse checkChargeStatus(String pspReference) {
        try {
            PspChargeStatusResponse response = restClient.get()
                    .uri("/v1/charges/{pspReference}", pspReference)
                    .retrieve()
                    .body(PspChargeStatusResponse.class);
            if (response == null || response.status() == null) {
                throw new PspUnavailableException("PSP status response was empty or had no status", null);
            }
            return response;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new PspChargeNotFoundException(pspReference);
            }
            if (isRetryable(e)) {
                throw new PspUnavailableException("PSP asked us to retry (HTTP " + e.getStatusCode().value() + ")", e);
            }
            throw new PspRejectedException("PSP status check failed (HTTP %d): %s"
                    .formatted(e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString())));
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PspUnavailableException("PSP unavailable: " + e.getMessage(), e);
        }
    }

    private static boolean isRetryable(HttpClientErrorException e) {
        return e.getStatusCode().isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                || e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
