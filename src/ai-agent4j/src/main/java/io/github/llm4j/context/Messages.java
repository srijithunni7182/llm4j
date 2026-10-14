package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Markers that context management keeps in a message's {@code providerData}. Providers ignore keys they do not know. */
public final class Messages {

    /** Set on a message a compaction strategy created or changed; the value is the strategy's name. */
    public static final String COMPACTION = "llm4j.compaction";
    /** On a "messages were removed" marker: how many messages it stands for, cumulatively. */
    public static final String DROPPED = "llm4j.compaction.dropped";
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
        return message.getProviderData().get(COMPACTION) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** How many messages a "removed" marker stands for; zero for any other message. */
    public static int dropped(Message message) {
        return message.getProviderData().get(DROPPED) instanceof Number n ? n.intValue() : 0;
    }

    /** The same message, marked as created or changed by this compaction strategy (rule 8 of {@link ContextCompactor}). */
    public static Message marked(Message message, String strategy) {
        return with(message, COMPACTION, strategy);
    }

    static Message with(Message message, String key, Object value) {
        Map<String, Object> data = new HashMap<>(message.getProviderData());
        data.put(key, value);
        return message.toBuilder().providerData(data).build();
    }
}
