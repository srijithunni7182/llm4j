package io.github.llm4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * What the runtime hands to an {@link AgentChannel}: something that came from outside and needs a
 * decision. Every string in it is untrusted text from another party. The set is closed, so on JDK
 * 21 and later a {@code switch} over it needs no default branch and a new kind cannot be added
 * without every channel being updated.
 */
public sealed interface ChannelMessage {

    /**
     * A question to answer.
     *
     * @param text the question, untrusted
     * @param data structured context, or null
     * @param expecting the shape the answer must have (a JSON Schema-like object), or null
     * @param attempt 1 for the first ask, higher when the last reply was rejected
     * @param rejectedBecause why the last reply was rejected, or null
     */
    record Question(
            String text, JsonNode data, JsonNode expecting, int attempt, String rejectedBecause)
            implements ChannelMessage {
        /** Requires the text and a positive attempt. */
        public Question {
            Objects.requireNonNull(text, "text");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be 1 or more");
            }
        }

        @Override
        public String toString() {
            return "Question[untrusted text, " + text.length() + " chars, attempt " + attempt + "]";
        }
    }

    /**
     * A request for authorization. The reply is only a message: a credential is obtained out of
     * band and never travels through a reply.
     *
     * @param description what the agent says it needs, untrusted
     */
    record AuthorizationNeeded(String description) implements ChannelMessage {
        /** Requires the description. */
        public AuthorizationNeeded {
            Objects.requireNonNull(description, "description");
        }

        @Override
        public String toString() {
            return "AuthorizationNeeded[untrusted text, " + description.length() + " chars]";
        }
    }

    /** A file or link offered by the remote. llm4j never follows a link; the channel decides. */
    sealed interface FileOffered extends ChannelMessage {
        /** The sender's name for the file; never a path. */
        String label();

        /** The media type the sender claims, or empty. */
        String claimedMediaType();

        /**
         * A link the channel may fetch (and must validate against server-side request forgery).
         *
         * @param url the address
         * @param label the sender's name for it
         * @param claimedMediaType what the sender says it is
         */
        record Link(String url, String label, String claimedMediaType) implements FileOffered {
            /** Requires the address. */
            public Link {
                Objects.requireNonNull(url, "url");
                label = label == null ? "" : label;
                claimedMediaType = claimedMediaType == null ? "" : claimedMediaType;
            }
        }

        /** A file whose bytes are already held. */
        final class Inline implements FileOffered {
            private final byte[] bytes;
            private final String label;
            private final String claimedMediaType;

            /**
             * Creates the offer; the bytes are copied.
             *
             * @param bytes the content
             * @param label the sender's name for it
             * @param claimedMediaType what the sender says it is
             */
            public Inline(byte[] bytes, String label, String claimedMediaType) {
                this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
                this.label = label == null ? "" : label;
                this.claimedMediaType = claimedMediaType == null ? "" : claimedMediaType;
            }

            /** Opens the bytes for reading. */
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }

            /** The size in bytes. */
            public int size() {
                return bytes.length;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public String claimedMediaType() {
                return claimedMediaType;
            }

            @Override
            public String toString() {
                return "Inline[" + bytes.length + " bytes, label=" + label + "]";
            }
        }
    }
}
