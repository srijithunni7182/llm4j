package io.github.llm4j.a2a;

import java.util.List;
import java.util.Objects;

/**
 * What llm4j needs to know about a remote agent's card, with no protocol version in its shape. All
 * text in it came from the remote and is untrusted.
 *
 * @param name the agent's name
 * @param description what it says it does
 * @param skills what it says it can do
 * @param endpoints the ways to reach it, in the card's order of preference
 * @param defaultInputMediaTypes the media types it accepts by default; empty when it does not say
 * @param extensions the extensions it declares
 * @param signature the signature status the transport reported
 * @param authSchemes the names of the authentication schemes it declares
 */
public record AgentCardView(
        String name,
        String description,
        List<Skill> skills,
        List<Endpoint> endpoints,
        List<String> defaultInputMediaTypes,
        List<Extension> extensions,
        SignatureStatus signature,
        List<String> authSchemes) {

    /** What the transport found out about a card's signature. llm4j does no cryptography. */
    public enum SignatureStatus {
        /** The card carries no signature. */
        NOT_SIGNED,
        /** The transport verified a signature. */
        VERIFIED,
        /** A signature is present and did not verify. Always refused. */
        FAILED,
        /** The transport did not look. */
        NOT_CHECKED
    }

    /**
     * One thing the agent says it can do.
     *
     * @param id the skill id
     * @param name its name
     * @param description what it does
     * @param tags tags
     * @param examples example requests
     * @param inputMediaTypes media types it takes; empty when not stated
     * @param outputMediaTypes media types it gives
     */
    public record Skill(
            String id,
            String name,
            String description,
            List<String> tags,
            List<String> examples,
            List<String> inputMediaTypes,
            List<String> outputMediaTypes) {
        /** Requires an id; copies the lists. */
        public Skill {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("skill id must not be blank");
            }
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            examples = examples == null ? List.of() : List.copyOf(examples);
            inputMediaTypes = inputMediaTypes == null ? List.of() : List.copyOf(inputMediaTypes);
            outputMediaTypes = outputMediaTypes == null ? List.of() : List.copyOf(outputMediaTypes);
        }
    }

    /**
     * One way to reach the agent.
     *
     * @param url the address
     * @param binding the binding as the card states it (for example {@code JSONRPC})
     * @param protocolVersion the protocol version as the card states it
     * @param tenant an optional tenant passed through untouched, or null
     */
    public record Endpoint(String url, String binding, String protocolVersion, String tenant) {
        /** Requires the address, binding and version. */
        public Endpoint {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(protocolVersion, "protocolVersion");
        }
    }

    /**
     * An extension the card declares.
     *
     * @param uri the extension URI
     * @param required whether the agent refuses callers that do not support it
     */
    public record Extension(String uri, boolean required) {
        /** Requires the URI. */
        public Extension {
            if (uri == null || uri.isBlank()) {
                throw new IllegalArgumentException("extension uri must not be blank");
            }
        }
    }

    /** Requires a name; copies the lists; a missing signature status is {@code NOT_CHECKED}. */
    public AgentCardView {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        skills = skills == null ? List.of() : List.copyOf(skills);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        defaultInputMediaTypes =
                defaultInputMediaTypes == null ? List.of() : List.copyOf(defaultInputMediaTypes);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        signature = signature == null ? SignatureStatus.NOT_CHECKED : signature;
        authSchemes = authSchemes == null ? List.of() : List.copyOf(authSchemes);
    }

    /** Starts a builder. */
    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    /** Builds a view; every setting is optional. */
    public static final class Builder {
        private final String name;
        private final String description;
        private List<Skill> skills = List.of();
        private List<Endpoint> endpoints = List.of();
        private List<String> inputTypes = List.of();
        private List<Extension> extensions = List.of();
        private SignatureStatus signature = SignatureStatus.NOT_CHECKED;
        private List<String> authSchemes = List.of();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** Sets the skills. */
        public Builder skills(List<Skill> value) {
            this.skills = value;
            return this;
        }

        /** Sets the endpoints, in order of preference. */
        public Builder endpoints(List<Endpoint> value) {
            this.endpoints = value;
            return this;
        }

        /** Sets the media types accepted by default. */
        public Builder defaultInputMediaTypes(List<String> value) {
            this.inputTypes = value;
            return this;
        }

        /** Sets the declared extensions. */
        public Builder extensions(List<Extension> value) {
            this.extensions = value;
            return this;
        }

        /** Sets the signature status. */
        public Builder signature(SignatureStatus value) {
            this.signature = value;
            return this;
        }

        /** Sets the authentication scheme names. */
        public Builder authSchemes(List<String> value) {
            this.authSchemes = value;
            return this;
        }

        /** Builds the view. */
        public AgentCardView build() {
            return new AgentCardView(
                    name,
                    description,
                    skills,
                    endpoints,
                    inputTypes,
                    extensions,
                    signature,
                    authSchemes);
        }
    }
}
