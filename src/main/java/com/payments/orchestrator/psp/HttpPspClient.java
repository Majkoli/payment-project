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

/**
 * HTTP implementation against the PSP at {@code psp.base-url} (WireMock in local/dev/test).
 * Expects {@code POST /v1/payments} to answer with {@code {"id": "<pspReference>"}}.
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
    public PspChargeResult createCharge(PspChargeRequest request) {
        try {
            PspPaymentResponse response = restClient.post()
                    .uri("/v1/payments")
                    .header(IDEMPOTENCY_HEADER, request.reference().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PspPaymentResponse.class);
            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new PspUnavailableException("PSP response did not contain a payment id", null);
            }
            return new PspChargeResult(response.id());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                    || e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                throw new PspUnavailableException("PSP asked us to retry (HTTP " + e.getStatusCode().value() + ")", e);
            }
            throw new PspRejectedException("PSP rejected charge (HTTP %d): %s"
                    .formatted(e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString())));
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new PspUnavailableException("PSP unavailable: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private record PspPaymentResponse(String id) {
    }
}
