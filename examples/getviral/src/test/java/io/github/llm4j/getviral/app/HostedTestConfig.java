package io.github.llm4j.getviral.app;

import io.github.llm4j.getviral.config.GetViralConfig;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Demo model, offline API samples, tiny Reel renders — fast and deterministic. */
@TestConfiguration
public class HostedTestConfig {

    @Bean
    @Primary
    GetViralConfig testGetViralConfig(@Value("${getviral.data-dir}") String dataDir) {
        return GetViralConfig.from(java.util.Map.of("GETVIRAL_MODE", "demo", "GETVIRAL_OFFLINE_APIS", "true",
                "GETVIRAL_REEL_SIZE", "180x320", "GETVIRAL_IMAGE_PROVIDER", "local")).withDataDir(Path.of(dataDir));
    }
}
