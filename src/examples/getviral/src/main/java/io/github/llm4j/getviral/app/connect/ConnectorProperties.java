package io.github.llm4j.getviral.app.connect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth app credentials for each platform (register apps with Meta, Google and X). YouTube reuses the
 * Google OAuth client used for sign-in unless its own is given.
 */
@ConfigurationProperties("getviral.connect")
public record ConnectorProperties(App instagram, App youtube, App x) {

    public record App(String clientId, String clientSecret) {
        public boolean configured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public App app(Platform platform) {
        App app = switch (platform) {
            case INSTAGRAM -> instagram;
            case YOUTUBE -> youtube;
            case X -> x;
        };
        return app == null ? new App(null, null) : app;
    }
}
