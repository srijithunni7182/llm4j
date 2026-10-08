package io.github.llm4j.getviral.app.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A creator account. The profile fields become the defaults for every brief. */
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String email;
    private String name;
    private String avatarUrl;
    @Column(nullable = false)
    private String authProvider;
    private String handle;
    private String niche;
    private String tone;
    private String audience;
    private String region;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OnboardingStep onboardingStep;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant lastSeenAt;

    protected UserAccount() { }

    public static UserAccount create(String email, String name, String avatarUrl, String provider) {
        UserAccount user = new UserAccount();
        user.id = UUID.randomUUID().toString();
        user.email = email.toLowerCase();
        user.name = name;
        user.avatarUrl = avatarUrl;
        user.authProvider = provider;
        user.onboardingStep = OnboardingStep.PROFILE;
        user.createdAt = Instant.now();
        user.lastSeenAt = user.createdAt;
        return user;
    }

    public String getId() { return id; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getAuthProvider() { return authProvider; }
    public String getHandle() { return handle; }
    public String getNiche() { return niche; }
    public String getTone() { return tone; }
    public String getAudience() { return audience; }
    public String getRegion() { return region; }
    public OnboardingStep getOnboardingStep() { return onboardingStep; }
    public Instant getCreatedAt() { return createdAt; }

    public void seen(String name, String avatarUrl) {
        if (name != null && !name.isBlank()) this.name = name;
        if (avatarUrl != null && !avatarUrl.isBlank()) this.avatarUrl = avatarUrl;
        this.lastSeenAt = Instant.now();
    }

    public void updateProfile(String handle, String niche, String tone, String audience, String region) {
        this.handle = handle;
        this.niche = niche;
        this.tone = tone;
        this.audience = audience;
        this.region = region;
    }

    public void onboardingStep(OnboardingStep step) {
        this.onboardingStep = step;
    }
}
