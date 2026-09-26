package io.github.llm4j.getviral.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code getviral.*} settings — see application.yml for the environment variables behind each. */
@ConfigurationProperties("getviral")
public record AppProperties(
        @DefaultValue("getviral-data") String dataDir,
        @DefaultValue("http://localhost:7070") String publicUrl,
        String tokenKey,
        @DefaultValue Quota quota,
        @DefaultValue Auth auth,
        @DefaultValue Media media) {

    public record Quota(@DefaultValue("20") int packsPerMonth, @DefaultValue("8") int workers,
                        @DefaultValue("10m") Duration humanTimeout) { }

    public record Auth(@DefaultValue("false") boolean devLogin, String googleClientId, String googleClientSecret) {
        public boolean googleConfigured() {
            return googleClientId != null && !googleClientId.isBlank()
                    && googleClientSecret != null && !googleClientSecret.isBlank();
        }
    }

    public record Media(String gcsBucket) {
        public boolean gcsConfigured() {
            return gcsBucket != null && !gcsBucket.isBlank();
        }
    }
}
