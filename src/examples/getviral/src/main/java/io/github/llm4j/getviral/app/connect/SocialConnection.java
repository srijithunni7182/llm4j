package io.github.llm4j.getviral.app.connect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A creator's linked Instagram / YouTube / X account. Tokens are stored encrypted. */
@Entity
@Table(name = "social_connections")
public class SocialConnection {

    @Id
    private String id;
    @Column(nullable = false)
    private String userId;
    @Column(nullable = false)
    private String platform;
    private String externalId;
    private String username;
    private String displayName;
    private String avatarUrl;
    private String accessTokenEnc;
    private String refreshTokenEnc;
    private Instant expiresAt;
    private String scopes;
    @Column(nullable = false)
    private Instant connectedAt;

    protected SocialConnection() { }

    public static SocialConnection of(String userId, String platform) {
        SocialConnection c = new SocialConnection();
        c.id = UUID.randomUUID().toString();
        c.userId = userId;
        c.platform = platform;
        c.connectedAt = Instant.now();
        return c;
    }

    public void update(String externalId, String username, String displayName, String avatarUrl,
                       String accessTokenEnc, String refreshTokenEnc, Instant expiresAt, String scopes) {
        this.externalId = externalId;
        this.username = username;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.accessTokenEnc = accessTokenEnc;
        if (refreshTokenEnc != null) this.refreshTokenEnc = refreshTokenEnc;
        this.expiresAt = expiresAt;
        this.scopes = scopes;
        this.connectedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public String getPlatform() { return platform; }
    public String getExternalId() { return externalId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getAccessTokenEnc() { return accessTokenEnc; }
    public String getRefreshTokenEnc() { return refreshTokenEnc; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getScopes() { return scopes; }
    public Instant getConnectedAt() { return connectedAt; }
}
