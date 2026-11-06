package io.github.llm4j.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The limits media must stay within: how large one item may be, how many a message may carry, how
 * many bytes a whole request may hold, and which media types are allowed for each kind. A kind with
 * no allowed type is refused outright, so a missing entry never means "anything".
 *
 * @param maxBytesPerItem the most bytes one {@link Media} may hold
 * @param maxItemsPerMessage the most items one message may carry
 * @param maxBytesPerRequest the most media bytes a whole request may carry
 * @param allowedTypes the media types allowed for each kind; a kind missing here is refused
 */
public record MediaPolicy(
        long maxBytesPerItem,
        int maxItemsPerMessage,
        long maxBytesPerRequest,
        Map<MediaKind, Set<String>> allowedTypes) {

    /** 20 MiB: within what the vendors take inline, and well above a photo or a short clip. */
    public static final long DEFAULT_MAX_BYTES_PER_ITEM = 20L * 1024 * 1024;

    /** Four items in one message: enough to compare a few pictures, few enough to stay cheap. */
    public static final int DEFAULT_MAX_ITEMS_PER_MESSAGE = 4;

    /** 32 MiB for the whole request, the smallest of the vendors' inline limits. */
    public static final long DEFAULT_MAX_BYTES_PER_REQUEST = 32L * 1024 * 1024;

    /** Checks the limits are positive and keeps its own copy of the allowed types. */
    public MediaPolicy {
        requirePositive("maxBytesPerItem", maxBytesPerItem);
        requirePositive("maxItemsPerMessage", maxItemsPerMessage);
        requirePositive("maxBytesPerRequest", maxBytesPerRequest);
        Map<MediaKind, Set<String>> copy = new EnumMap<>(MediaKind.class);
        for (MediaKind kind : MediaKind.values()) {
            copy.put(kind, Set.copyOf(allowedTypes.getOrDefault(kind, Set.of())));
        }
        allowedTypes = Map.copyOf(copy);
    }

    /** The default limits, allowing every format the library recognises. */
    public static MediaPolicy defaults() {
        return builder().build();
    }

    /** A builder that starts from {@link #defaults()}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether media of this kind and type is allowed. */
    public boolean allows(MediaKind kind, String mediaType) {
        return allowedTypes.get(kind).contains(mediaType);
    }

    private static void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    /** Builds a {@link MediaPolicy}. */
    public static final class Builder {
        private long maxBytesPerItem = DEFAULT_MAX_BYTES_PER_ITEM;
        private int maxItemsPerMessage = DEFAULT_MAX_ITEMS_PER_MESSAGE;
        private long maxBytesPerRequest = DEFAULT_MAX_BYTES_PER_REQUEST;
        private final Map<MediaKind, Set<String>> allowed = new EnumMap<>(MediaSniffer.SUPPORTED);

        private Builder() {}

        /** The most bytes one item may hold. */
        public Builder maxBytesPerItem(long bytes) {
            requirePositive("maxBytesPerItem", bytes);
            this.maxBytesPerItem = bytes;
            return this;
        }

        /** The most items one message may carry. */
        public Builder maxItemsPerMessage(int items) {
            requirePositive("maxItemsPerMessage", items);
            this.maxItemsPerMessage = items;
            return this;
        }

        /** The most media bytes a whole request may carry. */
        public Builder maxBytesPerRequest(long bytes) {
            requirePositive("maxBytesPerRequest", bytes);
            this.maxBytesPerRequest = bytes;
            return this;
        }

        /**
         * Allows only these media types for the kind, replacing the earlier list.
         *
         * @throws IllegalArgumentException for a type the library cannot recognise, because it
         *     could never be matched and would only look like permission
         */
        public Builder allow(MediaKind kind, String... mediaTypes) {
            Set<String> known = MediaSniffer.SUPPORTED.get(kind);
            for (String type : mediaTypes) {
                if (!known.contains(type)) {
                    throw new IllegalArgumentException(
                            type
                                    + " is not a supported "
                                    + kind.label()
                                    + " type; supported: "
                                    + String.join(", ", new java.util.TreeSet<>(known)));
                }
            }
            allowed.put(kind, Set.of(mediaTypes));
            return this;
        }

        /** Refuses the kind outright. */
        public Builder refuse(MediaKind kind) {
            allowed.put(kind, Set.of());
            return this;
        }

        /** Builds it. */
        public MediaPolicy build() {
            return new MediaPolicy(
                    maxBytesPerItem, maxItemsPerMessage, maxBytesPerRequest, allowed);
        }
    }
}
