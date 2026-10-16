package com.payments.orchestrator.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Webhook body sent by the PSP. Both camelCase and snake_case field names are accepted.
 *
 * @param eventId       PSP-unique event id, used for deduplication
 * @param eventType     e.g. {@code charge.authorized}
 * @param pspReference  the PSP's id of the charge the event applies to
 * @param amountMinor   optional; when present it is checked against the charge amount
 * @param failureReason optional; stored on the charge for {@code charge.failed}
 */
public record PspWebhookPayload(
        @JsonAlias("event_id") String eventId,
        @JsonAlias("event_type") String eventType,
        @JsonAlias("psp_reference") String pspReference,
        @JsonAlias("amount_minor") Long amountMinor,
        @JsonAlias("failure_reason") String failureReason) {
}
