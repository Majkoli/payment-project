package com.payments.orchestrator;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Shared infrastructure for integration tests, started once per JVM:
 * <ul>
 *   <li>PostgreSQL and Redis via Testcontainers when Docker is available (CI, most dev machines).</li>
 *   <li>Without Docker, PostgreSQL falls back to an embedded real PostgreSQL binary so the suite
 *       still runs; Redis is then not started (the application does not use it yet and its
 *       connections are lazy).</li>
 *   <li>WireMock, in-process on a dynamic port, plays the PSP; {@code psp.base-url} is overridden.</li>
 * </ul>
 */
public abstract class AbstractIntegrationTest {

    protected static final WireMockServer PSP = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    /** True when PostgreSQL and Redis run in Testcontainers (false: embedded PostgreSQL fallback). */
    protected static final boolean USING_CONTAINERS;

    private static final String JDBC_URL;
    private static final String DB_USER;
    private static final String DB_PASSWORD;
    private static final String REDIS_HOST;
    private static final Integer REDIS_PORT;

    static {
        PSP.start();
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
                postgres.start();
                JDBC_URL = postgres.getJdbcUrl();
                DB_USER = postgres.getUsername();
                DB_PASSWORD = postgres.getPassword();

                GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
                redis.start();
                REDIS_HOST = redis.getHost();
                REDIS_PORT = redis.getMappedPort(6379);
                USING_CONTAINERS = true;
            } else {
                EmbeddedPostgres embedded = EmbeddedPostgres.builder().start();
                JDBC_URL = embedded.getJdbcUrl("postgres", "postgres");
                DB_USER = "postgres";
                DB_PASSWORD = "postgres";
                REDIS_HOST = null;
                REDIS_PORT = null;
                USING_CONTAINERS = false;
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
        if (REDIS_HOST != null) {
            registry.add("spring.data.redis.host", () -> REDIS_HOST);
            registry.add("spring.data.redis.port", () -> REDIS_PORT);
        }
    }
}
