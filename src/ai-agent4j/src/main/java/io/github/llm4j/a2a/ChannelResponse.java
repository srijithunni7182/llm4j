package io.github.llm4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** What an {@link AgentChannel} answers. The set is closed. */
public sealed interface ChannelResponse {

    /**
     * An answer.
     *
     * @param text the answer text; may be empty when data is given
     * @param data a structured answer, or null
     */
    record Reply(String text, JsonNode data) implements ChannelResponse {
        /** Requires the text (use "" for none). */
        public Reply {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * The answer will come later; the consumer completes the token.
     *
     * @param token an id the consumer uses to complete it
     */
    record Defer(String token) implements ChannelResponse {
        /** Requires a token. */
        public Defer {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("token must not be blank");
            }
        }
    }

    /**
     * The channel declines to answer; the step fails.
     *
     * @param reason why
     */
    record Refuse(String reason) implements ChannelResponse {
        /** Requires a reason. */
        public Refuse {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * The files were accepted, written through {@link ChannelContext#receiveFile}.
     *
     * @param files what the sink recorded
     */
    record Received(List<ReceivedFile> files) implements ChannelResponse {
        /** Copies the list. */
        public Received {
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }
    }

    /** A file or link is dropped on purpose. */
    record Ignore() implements ChannelResponse {}
}
