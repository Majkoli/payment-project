package com.payments.orchestrator.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 404: no charge has this PSP reference (yet). Deliberately an error so the PSP retries: a webhook
 * can legitimately outrun our own bookkeeping of the reference returned by the charge call.
 */
public class WebhookChargeNotFoundException extends ResponseStatusException {

    public WebhookChargeNotFoundException(String pspReference) {
        super(HttpStatus.NOT_FOUND, "No charge found for PSP reference " + pspReference);
    }
}
