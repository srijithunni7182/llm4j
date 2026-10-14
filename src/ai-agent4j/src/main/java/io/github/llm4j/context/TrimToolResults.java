package io.github.llm4j.context;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shortens large tool results, oldest first, keeping the head and the tail of each, until the target is reached. */
final class TrimToolResults implements ContextCompactor {

    static final String NAME = "trim_tool_results";
    static final int MIN_CHARS = 40;

    private final int maxChars;

    TrimToolResults(int maxChars) {
        if (maxChars < MIN_CHARS) throw new IllegalArgumentException("maxChars must be at least " + MIN_CHARS + ", got " + maxChars);
        this.maxChars = maxChars;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CompactionResult compact(List<Message> messages, CompactionRequest request) {
        if (messages.isEmpty()) return CompactionResult.unchanged(NAME, messages, request, CompactionResult.Outcome.SKIPPED, "nothing to compact");
        long size = request.sizeOf(messages);
        if (size <= request.targetTokens()) {
            return CompactionResult.unchanged(NAME, messages, request, CompactionResult.Outcome.SKIPPED, "already within the target");
        }
        Exchanges.Region region = Exchanges.region(messages, request.keepRecent(), request.protectTask());
        List<Integer> at = new ArrayList<>();
        List<Message> shorter = new ArrayList<>();
        List<Long> saving = new ArrayList<>();
        for (int i = region.prefixEnd(); i < region.tailStart(); i++) {
            Message m = messages.get(i);
            if (m.getRole() != Message.Role.TOOL || request.pinned().test(m) || Messages.compactionOf(m).isPresent()) continue;
            Message t = trimmed(m);
            if (t == null) continue;
            at.add(i);
            shorter.add(t);
            saving.add(request.sizeOf(List.of(m)) - request.sizeOf(List.of(t)));
        }
        if (at.isEmpty()) return CompactionResult.unchanged(NAME, messages, request, CompactionResult.Outcome.NO_GAIN, "no large tool results to shorten");

        int k = 1;
        long saved = saving.get(0);
        while (k < at.size() && size - saved > request.targetTokens()) saved += saving.get(k++);
        List<Message> candidate = replaced(messages, at, shorter, k);
        while (request.sizeOf(candidate) > request.targetTokens() && k < at.size()) candidate = replaced(messages, at, shorter, ++k);
        return CompactionResult.of(NAME, messages, candidate, request);
    }

    private static List<Message> replaced(List<Message> messages, List<Integer> at, List<Message> shorter, int k) {
        List<Message> out = new ArrayList<>(messages);
        for (int i = 0; i < k; i++) out.set(at.get(i), shorter.get(i));
        return out;
    }

    /** The shortened message, or null when shortening would not make it shorter. */
    private Message trimmed(Message m) {
        String text = m.getContent();
        if (text.length() <= maxChars) return null;
        int head = maxChars / 2;
        int tail = maxChars - head;
        int omitted = text.length() - head - tail;
        String cut = text.substring(0, head) + "\n[… " + omitted + " characters omitted …]\n" + text.substring(text.length() - tail);
        if (cut.length() >= text.length()) return null;
        return Messages.with(m.withContent(cut), Messages.COMPACTION, NAME);
    }
}
