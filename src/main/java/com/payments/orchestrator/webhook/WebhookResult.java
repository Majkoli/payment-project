package com.payments.orchestrator.webhook;

/** What happened to an authenticated webhook; all of these are acknowledged with HTTP 200. */
public enum WebhookResult {
    /** The event changed the charge. */
    PROCESSED,
    /** The event id was seen before; nothing was done. */
    DUPLICATE,
    /** The event was recorded but changed nothing (already reached, illegal, unknown type, amount mismatch). */
    IGNORED
}
