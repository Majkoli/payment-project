package com.payments.orchestrator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Shared infrastructure for integration tests. One PostgreSQL and one WireMock (the fake PSP)
 * are started once per JVM.
 *
 * <p>PostgreSQL comes from Testcontainers when Docker is available (CI) and falls back to an
 * embedded real PostgreSQL binary otherwise, so the suite runs on any developer machine.
 * Redis is not required by the tests (connections are lazy and unused so far).
 */
public abstract class AbstractIntegrationTest {

    protected static final WireMockServer PSP = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final String JDBC_URL;
    private static final String DB_USER;
    private static final String DB_PASSWORD;

    static {
        PSP.start();
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
                postgres.start();
                JDBC_URL = postgres.getJdbcUrl();
                DB_USER = postgres.getUsername();
                DB_PASSWORD = postgres.getPassword();
            } else {
                EmbeddedPostgres embedded = EmbeddedPostgres.builder().start();
                JDBC_URL = embedded.getJdbcUrl("postgres", "postgres");
                DB_USER = "postgres";
                DB_PASSWORD = "postgres";
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
        registry.add("psp.base-url", () -> "http://localhost:" + PSP.port());
        registry.add("reconciliation.cron", () -> "-"); // tests call the worker directly
    }
}
