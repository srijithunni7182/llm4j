package io.github.llm4j.a2a;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Turns a declaration (name, description, skills, accepted media types) plus the host's details
 * (public address, authentication, provider) into a card, and {@link A2AJsonRpc10} writes it as the
 * 1.0 JSON. It runs nothing. The declared capabilities default to no streaming, no push
 * notifications and no extended card, because llm4j offers none of them itself; a host that provides
 * them says so. Signing a card is the host's job.
 */
public final class AgentCardBuilder {

    /** A card ready to serve. */
    public record Published(AgentCardView view, String json, String etag) {}

    private final String name;
    private final String description;
    private final List<AgentCardView.Skill> skills = new ArrayList<>();
    private final List<AgentCardView.Endpoint> endpoints = new ArrayList<>();
    private final List<String> inputTypes = new ArrayList<>();
    private final List<String> outputTypes = new ArrayList<>();
    private final List<String> authSchemes = new ArrayList<>();
    private String version = "1.0.0";
    private boolean streaming;
    private boolean pushNotifications;
    private boolean extendedCard;
    private String providerOrganization;
    private String providerUrl;

    private AgentCardBuilder(String name, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
    }

    /** Starts a card for an agent. */
    public static AgentCardBuilder of(String name, String description) {
        return new AgentCardBuilder(name, description);
    }

    /** Declares a skill. */
    public AgentCardBuilder skill(AgentCardView.Skill skill) {
        skills.add(Objects.requireNonNull(skill, "skill"));
        return this;
    }

    /** Declares a media type the agent accepts by default (and produces, unless output types are set). */
    public AgentCardBuilder acceptsMediaType(String mediaType) {
        inputTypes.add(Objects.requireNonNull(mediaType, "mediaType"));
        return this;
    }

    /** Declares a media type the agent produces by default. */
    public AgentCardBuilder producesMediaType(String mediaType) {
        outputTypes.add(Objects.requireNonNull(mediaType, "mediaType"));
        return this;
    }

    /** Adds an interface the host serves; several may be listed, in order of preference. */
    public AgentCardBuilder endpoint(AgentCardView.Endpoint endpoint) {
        endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
        return this;
    }

    /** Names an authentication scheme the host requires. */
    public AgentCardBuilder authScheme(String scheme) {
        authSchemes.add(Objects.requireNonNull(scheme, "scheme"));
        return this;
    }

    /** The agent's own version (default {@code 1.0.0}). */
    public AgentCardBuilder version(String value) {
        this.version = Objects.requireNonNull(value, "version");
        return this;
    }

    /** The organisation that provides the agent. */
    public AgentCardBuilder provider(String organization, String url) {
        this.providerOrganization = Objects.requireNonNull(organization, "organization");
        this.providerUrl = url;
        return this;
    }

    /** Declares that the host streams; llm4j itself does not. */
    public AgentCardBuilder streaming(boolean value) {
        this.streaming = value;
        return this;
    }

    /** Declares that the host sends push notifications; llm4j itself does not. */
    public AgentCardBuilder pushNotifications(boolean value) {
        this.pushNotifications = value;
        return this;
    }

    /** Declares that the host serves an extended card; llm4j itself does not. */
    public AgentCardBuilder extendedCard(boolean value) {
        this.extendedCard = value;
        return this;
    }

    /**
     * Builds the card.
     *
     * @throws IllegalStateException if there is no endpoint, no skill or no accepted media type
     */
    public Published build() {
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("a card needs at least one endpoint: say where the host serves the agent");
        }
        if (skills.isEmpty()) {
            throw new IllegalStateException("a card needs at least one skill");
        }
        if (inputTypes.isEmpty()) {
            throw new IllegalStateException("a card needs the media types the agent accepts");
        }
        AgentCardView view = new AgentCardView(name, description, skills, endpoints, inputTypes, List.of(), AgentCardView.SignatureStatus.NOT_SIGNED, authSchemes);
        List<String> outputs = outputTypes.isEmpty() ? inputTypes : outputTypes;
        String json = A2AJsonRpc10.writeCard(new A2AJsonRpc10.CardSpec(view, version, outputs, streaming, pushNotifications, extendedCard, providerOrganization, providerUrl));
        return new Published(view, json, etag(json));
    }

    /** A strong validator derived from the card's content, for the host's caching headers. */
    static String etag(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
