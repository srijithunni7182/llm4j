package io.github.llm4j.getviral.app.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.getviral.app.AppProperties;
import io.github.llm4j.getviral.app.account.ConnectionsView;
import io.github.llm4j.getviral.app.memory.VoiceSamples;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OAuth 2.0 authorization-code flows for linking a creator's Instagram, YouTube and X accounts, with
 * {@code state} (CSRF) and PKCE where the platform supports it. Tokens are encrypted before storage.
 * On connect, recent posts are imported as voice samples so agents write like the creator.
 */
@Service
public class ConnectService implements ConnectionsView {

    private static final Logger log = LoggerFactory.getLogger(ConnectService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConnectorProperties apps;
    private final AppProperties props;
    private final SocialConnectionRepository connections;
    private final TokenCipher cipher;
    private final VoiceSamples voices;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ConnectService(ConnectorProperties apps, AppProperties props, SocialConnectionRepository connections,
                          TokenCipher cipher, VoiceSamples voices) {
        this.apps = apps;
        this.props = props;
        this.connections = connections;
        this.cipher = cipher;
        this.voices = voices;
    }

    /** What the browser keeps between the redirect out and the callback (in the server-side session). */
    public record Pending(String state, String verifier, String returnTo) implements java.io.Serializable { }

    public boolean configured(Platform platform) {
        return apps.app(platform).configured();
    }

    public String redirectUri(Platform platform) {
        return props.publicUrl().replaceAll("/+$", "") + "/connect/" + platform.key() + "/callback";
    }

    public Pending newPending(String returnTo) {
        return new Pending(random(24), random(48), returnTo);
    }

    public String authorizeUrl(Platform platform, Pending pending) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", apps.app(platform).clientId());
        q.put("redirect_uri", redirectUri(platform));
        q.put("response_type", "code");
        q.put("scope", platform.scopes);
        q.put("state", pending.state());
        if (platform.pkce) {
            q.put("code_challenge", challenge(pending.verifier()));
            q.put("code_challenge_method", "S256");
        }
        if (platform == Platform.YOUTUBE) {
            q.put("access_type", "offline");
            q.put("prompt", "consent");
            q.put("include_granted_scopes", "true");
        }
        return platform.authorizeUrl + "?" + form(q);
    }

    /** Exchanges the code, fetches the profile, stores the encrypted tokens, imports recent posts. */
    public SocialConnection complete(String userId, Platform platform, String code, Pending pending)
            throws IOException, InterruptedException {
        ConnectorProperties.App app = apps.app(platform);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri(platform));
        body.put("client_id", app.clientId());
        if (platform.pkce) body.put("code_verifier", pending.verifier());
        HttpRequest.Builder tokenRequest = HttpRequest.newBuilder(URI.create(platform.tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20));
        if (platform == Platform.X) {
            // X confidential clients authenticate with HTTP Basic.
            tokenRequest.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (app.clientId() + ":" + app.clientSecret()).getBytes(StandardCharsets.UTF_8)));
        } else {
            body.put("client_secret", app.clientSecret());
        }
        JsonNode token = send(tokenRequest.POST(HttpRequest.BodyPublishers.ofString(form(body))).build());
        String access = token.path("access_token").asText();
        String refresh = token.path("refresh_token").asText(null);
        long expiresIn = token.path("expires_in").asLong(0);

        Profile profile;
        List<String> posts;
        switch (platform) {
            case INSTAGRAM -> {
                // Swap the short-lived token (1 hour) for a long-lived one (about 60 days).
                JsonNode longLived = send(HttpRequest.newBuilder(URI.create("https://graph.instagram.com/access_token?"
                        + form(Map.of("grant_type", "ig_exchange_token", "client_secret", app.clientSecret(),
                        "access_token", access)))).GET().build());
                access = longLived.path("access_token").asText(access);
                expiresIn = longLived.path("expires_in").asLong(expiresIn);
                JsonNode me = send(HttpRequest.newBuilder(URI.create("https://graph.instagram.com/me?"
                        + form(Map.of("fields", "user_id,username,name,profile_picture_url", "access_token", access)))).GET().build());
                profile = new Profile(me.path("user_id").asText(me.path("id").asText()), me.path("username").asText(),
                        me.path("name").asText(null), me.path("profile_picture_url").asText(null));
                posts = texts(get("https://graph.instagram.com/me/media?" + form(Map.of("fields", "caption",
                        "limit", "20", "access_token", access)), null), "data", "caption");
            }
            case YOUTUBE -> {
                JsonNode channels = send(bearer("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true", access));
                JsonNode channel = channels.path("items").path(0);
                JsonNode snippet = channel.path("snippet");
                profile = new Profile(channel.path("id").asText(), snippet.path("customUrl").asText(snippet.path("title").asText()),
                        snippet.path("title").asText(null), snippet.path("thumbnails").path("default").path("url").asText(null));
                JsonNode videos = get("https://www.googleapis.com/youtube/v3/search?part=snippet&forMine=true&type=video&maxResults=20", access);
                posts = new ArrayList<>();
                for (JsonNode item : videos.path("items")) {
                    String text = (item.path("snippet").path("title").asText("") + ". "
                            + item.path("snippet").path("description").asText("")).strip();
                    if (!text.isBlank()) posts.add(text);
                }
            }
            case X -> {
                JsonNode me = send(bearer("https://api.x.com/2/users/me?user.fields=profile_image_url,name,username", access)).path("data");
                profile = new Profile(me.path("id").asText(), me.path("username").asText(), me.path("name").asText(null),
                        me.path("profile_image_url").asText(null));
                posts = texts(get("https://api.x.com/2/users/" + profile.id()
                        + "/tweets?max_results=20&exclude=retweets,replies", access), "data", "text");
            }
            default -> throw new IllegalStateException("Unknown platform " + platform);
        }

        SocialConnection connection = connections.findByUserIdAndPlatform(userId, platform.key())
                .orElseGet(() -> SocialConnection.of(userId, platform.key()));
        connection.update(profile.id(), profile.username(), profile.name(), profile.avatar(), cipher.encrypt(access),
                refresh == null ? null : cipher.encrypt(refresh), expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null,
                token.path("scope").asText(platform.scopes));
        connections.save(connection);
        int imported = voices.add(userId, posts, platform.key());
        log.info("Connected {} for user {} (imported {} posts for voice)", platform.label, userId, imported);
        return connection;
    }

    public void disconnect(String userId, Platform platform) {
        connections.deleteByUserIdAndPlatform(userId, platform.key());
    }

    /** Decrypted Instagram publishing credentials for a creator, if connected. */
    public Optional<String[]> instagramCredentials(String userId) {
        return connections.findByUserIdAndPlatform(userId, Platform.INSTAGRAM.key())
                .filter(c -> c.getAccessTokenEnc() != null && c.getExternalId() != null)
                .filter(c -> c.getExpiresAt() == null || c.getExpiresAt().isAfter(Instant.now()))
                .map(c -> new String[] {c.getExternalId(), cipher.decrypt(c.getAccessTokenEnc()), c.getUsername()});
    }

    @Override
    public List<Map<String, Object>> forUser(String userId) {
        Map<String, SocialConnection> byPlatform = connections.findByUserId(userId).stream()
                .collect(Collectors.toMap(SocialConnection::getPlatform, c -> c));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            Map<String, Object> view = new LinkedHashMap<>();
            SocialConnection c = byPlatform.get(platform.key());
            view.put("platform", platform.key());
            view.put("label", platform.label);
            view.put("available", configured(platform));
            view.put("connected", c != null);
            if (c != null) {
                view.put("username", c.getUsername());
                view.put("displayName", c.getDisplayName());
                view.put("avatarUrl", c.getAvatarUrl());
                view.put("expired", c.getExpiresAt() != null && c.getExpiresAt().isBefore(Instant.now()));
            }
            view.put("publishing", platform == Platform.INSTAGRAM ? "supported" : "coming soon");
            out.add(view);
        }
        return out;
    }

    private record Profile(String id, String username, String name, String avatar) { }

    private JsonNode get(String url, String bearerToken) {
        try {
            return send(bearerToken == null ? HttpRequest.newBuilder(URI.create(url)).GET().build() : bearer(url, bearerToken));
        } catch (IOException e) {
            log.info("Optional import call failed ({}): {}", url.replaceAll("access_token=[^&]+", "access_token=***"), e.getMessage());
            return JSON.createObjectNode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JSON.createObjectNode();
        }
    }

    private static List<String> texts(JsonNode json, String array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : json.path(array)) {
            String text = item.path(field).asText("");
            if (!text.isBlank()) out.add(text);
        }
        return out;
    }

    private HttpRequest bearer(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(20)).GET().build();
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = JSON.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        if (response.statusCode() / 100 != 2) {
            String message = json.path("error_description").asText(json.path("error").path("message")
                    .asText(json.path("error").asText(json.path("detail").asText("HTTP " + response.statusCode()))));
            throw new IOException(message);
        }
        return json;
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
