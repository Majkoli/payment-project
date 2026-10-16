package com.payments.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param ttl               how long a completed response stays replayable
 * @param inProgressTimeout lease on an IN_PROGRESS claim; after it elapses a stuck (e.g. crashed)
 *                          request may be reclaimed by a retry
 */
@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(
        @DefaultValue("24h") Duration ttl,
        @DefaultValue("5m") Duration inProgressTimeout) {
}
