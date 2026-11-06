package io.github.llm4j.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * Represents a message in a conversation with an LLM. Messages can have different roles (system,
 * user, assistant) and contain text content.
 */
@JsonIgnoreProperties(value = "media", allowGetters = true)
public final class Message {

    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        /**
         * The result of a tool call, answering one of an assistant message's {@link #getToolCalls()
         * tool calls}.
         */
        TOOL("tool");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }

        @JsonCreator
        public static Role fromValue(String value) {
            for (Role role : values()) {
                if (role.value.equals(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }

    private final Role role;
    private final String content;
    private final String name;
    private final java.util.List<ToolCall> toolCalls;
    private final String toolCallId;
    private final java.util.Map<String, Object> providerData;
    private final java.util.List<Media> media;

    private Message(Builder builder) {
        this.role = Objects.requireNonNull(builder.role, "role cannot be null");
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.name = builder.name;
        this.toolCalls =
                builder.toolCalls == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(builder.toolCalls);
        this.toolCallId = builder.toolCallId;
        this.providerData =
                builder.providerData == null
                        ? java.util.Map.of()
                        : java.util.Map.copyOf(builder.providerData);
        this.media =
                builder.media == null ? java.util.List.of() : java.util.List.copyOf(builder.media);
        if (!media.isEmpty() && role != Role.USER) {
            throw new IllegalArgumentException(
                    "only a user message can carry media, not a "
                            + role.getValue()
                            + " message; put the media in a user message");
        }
    }

    @JsonCreator
    public Message(
            @JsonProperty("role") Role role,
            @JsonProperty("content") String content,
            @JsonProperty("name") String name) {
        this.role = Objects.requireNonNull(role, "role cannot be null");
        this.content = Objects.requireNonNull(content, "content cannot be null");
        this.name = name;
        this.toolCalls = java.util.List.of();
        this.toolCallId = null;
        this.providerData = java.util.Map.of();
        this.media = java.util.List.of();
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getName() {
        return name;
    }

    /** The tools an assistant message asked for (empty for every other message). */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /** For a {@link Role#TOOL} message, the id of the call it answers. */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * What a provider needs sent back unchanged with the tool results (Claude's thinking blocks,
     * Gemini's thought signatures). Opaque to everything but the provider that produced it.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.Map<String, Object> getProviderData() {
        return providerData;
    }

    /** This message with its text replaced (everything else kept). */
    /**
     * The images, sounds, videos and documents sent with the text; empty for most messages. Only a
     * user message can carry any. JSON carries their {@link Media#descriptor() descriptors}, never
     * their bytes, and reading a message back leaves the media for the caller to reload.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public java.util.List<Media> getMedia() {
        return media;
    }

    public Message withContent(String newContent) {
        return toBuilder().content(newContent).build();
    }

    public Builder toBuilder() {
        return builder()
                .role(role)
                .content(content)
                .name(name)
                .toolCalls(toolCalls)
                .toolCallId(toolCallId)
                .providerData(providerData)
                .media(media);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Message system(String content) {
        return builder().role(Role.SYSTEM).content(content).build();
    }

    public static Message user(String content) {
        return builder().role(Role.USER).content(content).build();
    }

    /** A user message with media beside its text. */
    public static Message user(String content, java.util.List<Media> media) {
        return builder().role(Role.USER).content(content).media(media).build();
    }

    public static Message assistant(String content) {
        return builder().role(Role.ASSISTANT).content(content).build();
    }

    /**
     * An assistant turn that asks for tools. {@code text} is whatever the model said alongside;
     * {@code providerData} may be null.
     */
    public static Message assistantToolCalls(
            String text,
            java.util.List<ToolCall> calls,
            java.util.Map<String, Object> providerData) {
        return builder()
                .role(Role.ASSISTANT)
                .content(text == null ? "" : text)
                .toolCalls(calls)
                .providerData(providerData)
                .build();
    }

    /** The result of tool call {@code callId} (null when the provider issues no ids). */
    public static Message toolResult(String callId, String toolName, String content) {
        return builder()
                .role(Role.TOOL)
                .content(content == null ? "" : content)
                .name(toolName)
                .toolCallId(callId)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return role == message.role
                && Objects.equals(content, message.content)
                && Objects.equals(name, message.name)
                && Objects.equals(toolCalls, message.toolCalls)
                && Objects.equals(toolCallId, message.toolCallId)
                && Objects.equals(providerData, message.providerData)
                && Objects.equals(media, message.media);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content, name, toolCalls, toolCallId, providerData, media);
    }

    @Override
    public String toString() {
        return "Message{"
                + "role="
                + role
                + ", content='"
                + content
                + '\''
                + (name != null ? ", name='" + name + '\'' : "")
                + (media.isEmpty() ? "" : ", media=" + media)
                + '}';
    }

    public static final class Builder {
        private Role role;
        private String content;
        private String name;
        private java.util.List<ToolCall> toolCalls;
        private String toolCallId;
        private java.util.Map<String, Object> providerData;
        private java.util.List<Media> media;

        private Builder() {}

        /** Media to send beside the text; only a user message can carry any. */
        public Builder media(java.util.List<Media> media) {
            this.media = media;
            return this;
        }

        public Builder toolCalls(java.util.List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder providerData(java.util.Map<String, Object> providerData) {
            this.providerData = providerData;
            return this;
        }

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }
}
