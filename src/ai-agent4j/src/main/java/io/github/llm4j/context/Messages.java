package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Markers that context management keeps in a message's {@code providerData}. Providers ignore keys
 * they do not know.
 */
public final class Messages {

    /**
     * Set on a message a compaction strategy created or changed; the value is the strategy's name.
     */
    public static final String COMPACTION = "llm4j.compaction";

    /** On a "messages were removed" marker: how many messages it stands for, cumulatively. */
    public static final String DROPPED = "llm4j.compaction.dropped";

    /**
     * On a marker or a summary: the media that went with the messages it stands for, as {@code kind
     * name -> count}, cumulatively.
     */
    public static final String DROPPED_MEDIA = "llm4j.compaction.dropped_media";

    /** Set (to true) on a message that must never be removed or changed by compaction. */
    public static final String PINNED = "llm4j.pinned";

    private Messages() {}

    /** The same message, pinned: compaction will keep it as it is. */
    public static Message pinned(Message message) {
        return with(message, PINNED, true);
    }

    public static boolean isPinned(Message message) {
        return Boolean.TRUE.equals(message.getProviderData().get(PINNED));
    }

    /** The strategy that created or changed this message, if any. */
    public static Optional<String> compactionOf(Message message) {
        return message.getProviderData().get(COMPACTION) instanceof String s
                ? Optional.of(s)
                : Optional.empty();
    }

    /** How many messages a "removed" marker stands for; zero for any other message. */
    public static int dropped(Message message) {
        return message.getProviderData().get(DROPPED) instanceof Number n ? n.intValue() : 0;
    }

    /**
     * The media a marker or summary stands for, by kind; empty for any other message. It is what
     * compaction removed earlier, kept so that a second compaction adds to it.
     */
    public static Map<io.github.llm4j.model.MediaKind, Integer> droppedMedia(Message message) {
        Map<io.github.llm4j.model.MediaKind, Integer> counts =
                new java.util.EnumMap<>(io.github.llm4j.model.MediaKind.class);
        if (message.getProviderData().get(DROPPED_MEDIA) instanceof Map<?, ?> stored) {
            stored.forEach(
                    (kind, n) -> {
                        if (n instanceof Number count && count.intValue() > 0) {
                            counts.put(
                                    io.github.llm4j.model.MediaKind.valueOf(String.valueOf(kind)),
                                    count.intValue());
                        }
                    });
        }
        return counts;
    }

    /** The media a message stands for: what it carries now plus what it stands in for. */
    static Map<io.github.llm4j.model.MediaKind, Integer> mediaStanding(Message message) {
        Map<io.github.llm4j.model.MediaKind, Integer> counts = droppedMedia(message);
        for (io.github.llm4j.model.Media item : message.getMedia())
            counts.merge(item.kind(), 1, Integer::sum);
        return counts;
    }

    /** The counts as providerData for a marker or summary; null when there is nothing to record. */
    static Map<String, Integer> asData(Map<io.github.llm4j.model.MediaKind, Integer> counts) {
        if (counts.isEmpty()) return null;
        Map<String, Integer> data = new java.util.LinkedHashMap<>();
        counts.forEach((kind, n) -> data.put(kind.name(), n));
        return data;
    }

    /** {@code 1 image}, {@code 2 images and 1 video}, {@code 1 image, 3 sounds and 2 documents}. */
    public static String describeMedia(Map<io.github.llm4j.model.MediaKind, Integer> counts) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (io.github.llm4j.model.MediaKind kind : io.github.llm4j.model.MediaKind.values()) {
            int n = counts.getOrDefault(kind, 0);
            if (n > 0) parts.add(n + " " + word(kind) + (n == 1 ? "" : "s"));
        }
        if (parts.size() <= 1) return String.join("", parts);
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and "
                + parts.get(parts.size() - 1);
    }

    private static String word(io.github.llm4j.model.MediaKind kind) {
        return switch (kind) {
            case AUDIO -> "sound";
            default -> kind.label();
        };
    }

    /**
     * The same message, marked as created or changed by this compaction strategy (rule 8 of {@link
     * ContextCompactor}).
     */
    public static Message marked(Message message, String strategy) {
        return with(message, COMPACTION, strategy);
    }

    /**
     * A test that accepts what the developer's own test accepts and every message marked with
     * {@link #pinned}.
     */
    public static java.util.function.Predicate<Message> orMarked(
            java.util.function.Predicate<Message> own) {
        return own == null ? Messages::isPinned : m -> isPinned(m) || own.test(m);
    }

    static Message with(Message message, String key, Object value) {
        Map<String, Object> data = new HashMap<>(message.getProviderData());
        data.put(key, value);
        return message.toBuilder().providerData(data).build();
    }
}
