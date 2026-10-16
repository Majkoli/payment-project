package com.payments.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "psp")
public record PspProperties(
        String baseUrl,
        Webhook webhook,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout) {

    public record Webhook(String secret) {
    }
}
