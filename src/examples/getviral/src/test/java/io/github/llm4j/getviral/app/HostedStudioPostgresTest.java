package io.github.llm4j.getviral.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Every hosted end-to-end test again, on real PostgreSQL 16 (the Cloud SQL engine) instead of H2 in
 * PostgreSQL mode: Flyway migrations, JDBC sessions, run events, SSE and memory sync. Skipped when
 * Docker isn't available, so {@code mvn test} still passes anywhere.
 */
@Testcontainers(disabledWithoutDocker = true)
class HostedStudioPostgresTest extends HostedStudioTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
