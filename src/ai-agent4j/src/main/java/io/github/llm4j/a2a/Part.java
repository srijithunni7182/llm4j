package io.github.llm4j.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** One piece of a message or artifact. Everything in it from a remote agent is untrusted. */
public sealed interface Part {

    /** Plain text. */
    record Text(String text) implements Part {
        /** Requires the text. */
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A structured JSON value. */
    record Data(JsonNode value) implements Part {
        /** Requires the value. */
        public Data {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A file, as a link to fetch or as bytes already held. The label and the media type are only
     * what the sender claims.
     */
    final class FileOffered implements Part {
        private final String url;
        private final byte[] bytes;
        private final String label;
        private final String claimedMediaType;

        private FileOffered(String url, byte[] bytes, String label, String claimedMediaType) {
            this.url = url;
            this.bytes = bytes;
            this.label = label == null ? "" : label;
            this.claimedMediaType = claimedMediaType == null ? "" : claimedMediaType;
        }

        /** A file offered as a link. llm4j never follows it. */
        public static FileOffered link(String url, String label, String claimedMediaType) {
            Objects.requireNonNull(url, "url");
            if (url.isBlank()) {
                throw new IllegalArgumentException("url must not be blank");
            }
            return new FileOffered(url, null, label, claimedMediaType);
        }

        /** A file whose bytes are already held; the bytes are copied. */
        public static FileOffered inline(byte[] bytes, String label, String claimedMediaType) {
            Objects.requireNonNull(bytes, "bytes");
            return new FileOffered(null, bytes.clone(), label, claimedMediaType);
        }

        /** Whether this is a link rather than held bytes. */
        public boolean isLink() {
            return url != null;
        }

        /** The address, for a link; otherwise null. */
        public String url() {
            return url;
        }

        /** A copy of the bytes, for an inline file; otherwise null. */
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }

        /** The sender's name for the file; never used to build a path. */
        public String label() {
            return label;
        }

        /** The media type the sender claims, or empty. */
        public String claimedMediaType() {
            return claimedMediaType;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FileOffered f
                    && Objects.equals(url, f.url)
                    && java.util.Arrays.equals(bytes, f.bytes)
                    && label.equals(f.label)
                    && claimedMediaType.equals(f.claimedMediaType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, java.util.Arrays.hashCode(bytes), label, claimedMediaType);
        }

        @Override
        public String toString() {
            return isLink()
                    ? "FileOffered[link, label=" + label + "]"
                    : "FileOffered[inline, " + bytes.length + " bytes, label=" + label + "]";
        }
    }
}
