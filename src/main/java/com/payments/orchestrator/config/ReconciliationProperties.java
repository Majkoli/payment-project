package com.payments.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The schedule itself is configured through {@code reconciliation.cron} (read directly by the
 * {@code @Scheduled} annotation; set it to {@code -} to disable the job).
 *
 * @param staleAfter a PENDING charge older than this is considered orphaned or dropped
 */
@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(@DefaultValue("15m") Duration staleAfter) {
}
