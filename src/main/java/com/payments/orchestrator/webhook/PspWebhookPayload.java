package com.payments.orchestrator.webhook;

/**
 * Webhook body sent by the PSP.
 *
 * @param eventId       PSP-unique event id, used for deduplication
 * @param eventType     e.g. {@code charge.authorized}
 * @param pspReference  the PSP's id of the charge the event applies to
 * @param amountMinor   optional; when present it is checked against the charge amount
 * @param failureReason optional; stored on the charge for {@code charge.failed}
 */
public record PspWebhookPayload(
        String eventId,
        String eventType,
        String pspReference,
        Long amountMinor,
        String failureReason) {
}
