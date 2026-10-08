package io.github.llm4j.privacy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Keeps personal data away from a model: every message of every request (system, user, assistant — so
 * tasks, retrieved context and tool observations alike) has its PII replaced by a placeholder such as
 * {@code [EMAIL]} before the wrapped client sees it.
 *
 * <p>By default it masks personal identifiers — email, phone, SSN, card numbers, IP addresses — but
 * not URLs, which agents routinely need and which aren't personal by themselves. Pass the types to
 * mask to change that.
 */
public class MaskingLLMClient implements LLMClient {

    /** Personal identifiers: every {@link PIIType} except {@link PIIType#URL}. */
    public static final Set<PIIType> PERSONAL = EnumSet.complementOf(EnumSet.of(PIIType.URL));

    private final LLMClient delegate;
    private final PIIDetector detector;
    private final Set<PIIType> types;
    private final Consumer<Map<PIIType, Integer>> onMasked;

    public MaskingLLMClient(LLMClient delegate) {
        this(delegate, new RegexPIIDetector(), PERSONAL, masked -> {});
    }

    /**
     * @param onMasked told, for each request where something was masked, how many of each type
     */
    public MaskingLLMClient(LLMClient delegate, PIIDetector detector, Set<PIIType> types,
            Consumer<Map<PIIType, Integer>> onMasked) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.detector = Objects.requireNonNull(detector, "detector cannot be null");
        this.types = Set.copyOf(types);
        this.onMasked = Objects.requireNonNull(onMasked, "onMasked cannot be null");
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        return delegate.chat(masked(request));
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        return delegate.chatStream(masked(request));
    }

    @Override
    public boolean supportsToolCalling() {
        return delegate.supportsToolCalling();
    }

    private LLMRequest masked(LLMRequest request) {
        Map<PIIType, Integer> counts = new EnumMap<>(PIIType.class);
        List<Message> messages = new ArrayList<>();
        for (Message m : request.getMessages()) {
            Message.Builder b = m.toBuilder().content(mask(m.getContent(), detector, types, counts));
            if (!m.getToolCalls().isEmpty()) {
                // tool-call arguments are text the model produced from what it saw: mask them like any other text
                List<io.github.llm4j.model.ToolCall> calls = new ArrayList<>();
                for (io.github.llm4j.model.ToolCall c : m.getToolCalls()) calls.add(new io.github.llm4j.model.ToolCall(c.id(), c.name(), maskedArguments(c.arguments(), counts)));
                b.toolCalls(calls);
            }
            messages.add(b.build());
        }
        if (!counts.isEmpty()) onMasked.accept(counts);
        return request.toBuilder().messages(messages).build();
    }

    private Map<String, Object> maskedArguments(Map<String, Object> arguments, Map<PIIType, Integer> counts) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            out.put(e.getKey(), e.getValue() instanceof String s ? mask(s, detector, types, counts) : e.getValue());
        }
        return out;
    }

    /**
     * Replaces each detected entity of the given types with {@code [TYPE]}, counting them in
     * {@code counts} (which may be null).
     */
    public static String mask(String text, PIIDetector detector, Set<PIIType> types, Map<PIIType, Integer> counts) {
        if (text == null || text.isEmpty()) return text;
        List<PIIEntity> found = new ArrayList<>(detector.detect(text).getEntities().stream()
                .filter(e -> types.contains(e.getType()))
                .toList());
        if (found.isEmpty()) return text;
        found.sort(Comparator.comparingInt(PIIEntity::getStartIndex).reversed());
        StringBuilder out = new StringBuilder(text);
        int lastStart = Integer.MAX_VALUE;
        for (PIIEntity e : found) {
            if (e.getEndIndex() > lastStart) continue; // overlaps one already replaced
            out.replace(e.getStartIndex(), e.getEndIndex(), "[" + e.getType().name() + "]");
            lastStart = e.getStartIndex();
            if (counts != null) counts.merge(e.getType(), 1, Integer::sum);
        }
        return out.toString();
    }
}
