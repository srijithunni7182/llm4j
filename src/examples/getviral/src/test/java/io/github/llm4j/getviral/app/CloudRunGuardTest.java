package io.github.llm4j.getviral.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudRunGuardTest {

    private static AppProperties props(boolean devLogin, String google, String tokenKey, String bucket, String url) {
        return new AppProperties("getviral-data", url, tokenKey,
                new AppProperties.Quota(20, 8, Duration.ofMinutes(10)),
                new AppProperties.Auth(devLogin, google, google),
                new AppProperties.Media(bucket));
    }

    @Test
    void localRunsNeedNoSetup() {
        CloudRunGuard guard = new CloudRunGuard(props(true, null, null, null, "http://localhost:7070"), Map.of());
        assertThat(guard.problems()).isEmpty();
        guard.check();
    }

    @Test
    void cloudRunRefusesLaptopSettings() {
        CloudRunGuard guard = new CloudRunGuard(props(true, null, null, null, "http://localhost:7070"),
                Map.of("K_SERVICE", "getviral"));
        assertThat(guard.problems()).hasSize(6);
        assertThatThrownBy(guard::check).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GETVIRAL_DEV_LOGIN").hasMessageContaining("GETVIRAL_TOKEN_KEY");
    }

    @Test
    void cloudRunAcceptsAProductionSetup() {
        CloudRunGuard guard = new CloudRunGuard(props(false, "client", "a2V5", "getviral-media", "https://getviral.app"),
                Map.of("K_SERVICE", "getviral",
                        "GETVIRAL_DB_URL", "jdbc:postgresql:///getviral?cloudSqlInstance=p:r:i&socketFactory=com.google.cloud.sql.postgres.SocketFactory"));
        assertThat(guard.problems()).isEmpty();
    }
}
