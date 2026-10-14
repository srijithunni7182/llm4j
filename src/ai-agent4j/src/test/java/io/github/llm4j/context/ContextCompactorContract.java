package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The rules every {@link ContextCompactor} must obey (design §4.2, C1–C8). Extend this class and implement
 * {@link #subject()} to hold a strategy, built-in or your own, to them.
 */
abstract class ContextCompactorContract {

    static final long SEED = Long.getLong("seed", 42L);
    static final int GENERATED = Integer.getInteger("generated", 500);
    static final double[] TARGETS = {0.95, 0.50, 0.10};
    static final int[] KEEP_RECENT = {0, 4, 12};
    static final boolean[] PROTECT_TASK = {true, false};

    protected abstract ContextCompactor subject();

    /** C6 applies to strategies that make no model call. */
    protected boolean deterministic() {
        return true;
    }

    @TestFactory
    Stream<DynamicTest> holdsOnTheFixedConversations() {
        return ConversationFixtures.fixed().entrySet().stream().flatMap(e -> {
            List<Message> input = e.getValue();
            long size = request(1, 4, true).sizeOf(input);
            return java.util.Arrays.stream(KEEP_RECENT).boxed().flatMap(keep -> java.util.Arrays.stream(TARGETS).boxed().flatMap(fraction ->
                    java.util.stream.Stream.of(true, false).map(protect -> DynamicTest.dynamicTest(
                            e.getKey() + " @ " + (int) (fraction * 100) + "%, keep " + keep + (protect ? "" : ", task not protected"),
                            () -> check(e.getKey(), input, request(Math.max(1, (long) (size * fraction)), keep, protect))))));
        });
    }

    @Test
    void holdsOnGeneratedConversations() {
        for (int i = 0; i < GENERATED; i++) {
            List<Message> input = ConversationFixtures.generated(SEED, i);
            long size = request(1, 4, true).sizeOf(input);
            for (double fraction : TARGETS) {
                int keep = KEEP_RECENT[i % KEEP_RECENT.length];
                boolean protect = PROTECT_TASK[(i / KEEP_RECENT.length) % PROTECT_TASK.length];
                String label = "seed=" + SEED + " index=" + i + " target=" + fraction + " keep=" + keep + " protectTask=" + protect;
                check(label, input, request(Math.max(1, (long) (size * fraction)), keep, protect));
            }
        }
    }

    @Test
    void namesItself() {
        assertThat(subject().name()).isNotBlank();
    }

    static CompactionRequest request(long target, int keepRecent, boolean protectTask) {
        return CompactionRequest.builder(target).keepRecent(keepRecent).pinned(Messages::isPinned).protectTask(protectTask).build();
    }

    // ── the checks ─────────────────────────────────────────────────────────────────

    private void check(String label, List<Message> input, CompactionRequest req) {
        List<Message> snapshot = new ArrayList<>(input);
        List<Message> frozen = List.copyOf(input); // any attempt to change it throws

        CompactionResult result = subject().compact(frozen, req);
        assertThat(result).as(label).isNotNull();
        List<Message> out = result.messages();
        assertThat(result.strategy()).as(label + ": strategy").isNotBlank();

        // C1 pure
        assertThat(frozen).as(label + ": C1 input unchanged").containsExactlyElementsOf(snapshot);

        // C2 protected content stays
        for (Message required : required(input, req)) {
            assertThat(out).as(label + ": C2 keeps " + describe(required)).contains(required);
        }

        // C3 pairs stay whole
        assertPairsWhole(label, input, out);

        // C4 order, C8 marked
        Map<Message, Integer> position = new IdentityHashMap<>();
        for (int i = 0; i < input.size(); i++) position.put(input.get(i), i);
        int last = -1;
        for (Message m : out) {
            Integer at = position.get(m);
            if (at != null) {
                assertThat(at).as(label + ": C4 order").isGreaterThan(last);
                last = at;
            } else if (!input.contains(m)) {
                assertThat(Messages.compactionOf(m)).as(label + ": C8 synthesised message is marked: " + describe(m)).isPresent();
            }
        }

        // C5 not bigger
        long before = req.sizeOf(input);
        long after = req.sizeOf(out);
        assertThat(after).as(label + ": C5 not bigger").isLessThanOrEqualTo(before);
        assertThat(result.tokensBefore()).as(label + ": tokensBefore").isEqualTo(before);
        assertThat(result.tokensAfter()).as(label + ": tokensAfter").isEqualTo(after);
        if (after == before) {
            assertThat(result.outcome()).as(label + ": equal size is not a compaction")
                    .isIn(CompactionResult.Outcome.NO_GAIN, CompactionResult.Outcome.SKIPPED);
        }

        // C6 deterministic
        if (deterministic()) {
            assertThat(subject().compact(frozen, req).messages()).as(label + ": C6 deterministic").isEqualTo(out);
        }

        // C7 idempotent in effect
        CompactionResult again = subject().compact(List.copyOf(out), req);
        long afterAgain = req.sizeOf(again.messages());
        boolean quiet = again.outcome() == CompactionResult.Outcome.NO_GAIN || again.outcome() == CompactionResult.Outcome.SKIPPED;
        assertThat(quiet || afterAgain >= after * 0.90)
                .as(label + ": C7 a second pass gains nothing more (" + after + " -> " + afterAgain + ", " + again.outcome() + ")").isTrue();
    }

    /** What must survive: leading system messages, the first user message, the last N, and pinned messages. */
    private static List<Message> required(List<Message> input, CompactionRequest req) {
        List<Message> keep = new ArrayList<>();
        int i = 0;
        while (i < input.size() && input.get(i).getRole() == Message.Role.SYSTEM) keep.add(input.get(i++));
        for (int j = i; req.protectTask() && j < input.size(); j++) {
            if (input.get(j).getRole() == Message.Role.USER) {
                keep.add(input.get(j));
                break;
            }
        }
        for (int j = Math.max(0, input.size() - req.keepRecent()); j < input.size(); j++) keep.add(input.get(j));
        for (Message m : input) if (req.pinned().test(m)) keep.add(m);
        return keep;
    }

    private static void assertPairsWhole(String label, List<Message> input, List<Message> out) {
        Set<String> answeredInInput = new HashSet<>();
        for (Message m : input) if (m.getRole() == Message.Role.TOOL && m.getToolCallId() != null) answeredInInput.add(m.getToolCallId());
        Set<String> callsInOutput = new HashSet<>();
        Set<String> resultsInOutput = new HashSet<>();
        for (Message m : out) {
            for (ToolCall c : m.getToolCalls()) callsInOutput.add(c.id());
            if (m.getRole() == Message.Role.TOOL && m.getToolCallId() != null) resultsInOutput.add(m.getToolCallId());
        }
        for (String id : resultsInOutput) assertThat(callsInOutput).as(label + ": C3 result " + id + " has its call").contains(id);
        for (String id : callsInOutput) {
            if (answeredInInput.contains(id)) assertThat(resultsInOutput).as(label + ": C3 call " + id + " keeps its result").contains(id);
        }
    }

    private static String describe(Message m) {
        String c = m.getContent();
        return m.getRole() + ":" + (c.length() > 24 ? c.substring(0, 24) + "…" : c);
    }
}
