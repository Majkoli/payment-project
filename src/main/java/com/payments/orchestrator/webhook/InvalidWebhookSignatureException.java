package com.payments.orchestrator.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 401: the {@code X-Signature-SHA256} header is missing or does not match the body. */
public class InvalidWebhookSignatureException extends ResponseStatusException {

    public InvalidWebhookSignatureException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid or missing webhook signature.");
    }
}
