package com.payments.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {

    /** Injectable clock so time-dependent logic (expiry, leases, staleness) stays testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
