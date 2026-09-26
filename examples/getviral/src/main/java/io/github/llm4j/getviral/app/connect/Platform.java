package io.github.llm4j.getviral.app.connect;

import java.util.Locale;
import java.util.Optional;

/**
 * The social platforms a creator can connect, with their OAuth endpoints and the scopes GetViral asks
 * for. Scopes are the minimum for reading the creator's profile and recent posts (to learn their voice)
 * and for publishing on their behalf — every publish still needs the creator's approval in the studio.
 */
public enum Platform {

    INSTAGRAM("Instagram",
            "https://www.instagram.com/oauth/authorize",
            "https://api.instagram.com/oauth/access_token",
            "instagram_business_basic,instagram_business_content_publish", false),
    YOUTUBE("YouTube",
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/youtube.upload", true),
    X("X",
            "https://x.com/i/oauth2/authorize",
            "https://api.x.com/2/oauth2/token",
            "tweet.read tweet.write users.read offline.access", true);

    public final String label;
    public final String authorizeUrl;
    public final String tokenUrl;
    public final String scopes;
    public final boolean pkce;

    Platform(String label, String authorizeUrl, String tokenUrl, String scopes, boolean pkce) {
        this.label = label;
        this.authorizeUrl = authorizeUrl;
        this.tokenUrl = tokenUrl;
        this.scopes = scopes;
        this.pkce = pkce;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Platform> of(String key) {
        for (Platform p : values()) if (p.key().equalsIgnoreCase(key)) return Optional.of(p);
        return Optional.empty();
    }
}
