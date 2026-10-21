package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactionResultTest {

    private final CompactionRequest req = CompactionRequest.of(100);

    @Test
    void ofFillsInTheNumbers() {
        List<Message> before =
                new Convo().system("s").task("t").step("a", 2_000).step("b", 2_000).build();
        List<Message> after = List.of(before.get(0), before.get(1), before.get(4), before.get(5));
        CompactionResult r = CompactionResult.of("x", before, after, req);
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.messagesBefore()).isEqualTo(6);
        assertThat(r.messagesAfter()).isEqualTo(4);
        assertThat(r.droppedMessages()).isEqualTo(2);
        assertThat(r.tokensBefore()).isEqualTo(req.sizeOf(before));
        assertThat(r.tokensAfter()).isEqualTo(req.sizeOf(after));
    }

    @Test
    void aResultThatIsNotSmallerIsReplacedByTheOriginalWithNoGain() {
        List<Message> before = new Convo().system("s").task("t").step("a", 100).build();
        List<Message> bigger = new ArrayList<>(before);
        bigger.add(Message.user("x".repeat(5_000)));
        CompactionResult r = CompactionResult.of("greedy", before, bigger, req);
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.messages()).isEqualTo(before);
        assertThat(r.tokensAfter()).isEqualTo(r.tokensBefore());
        assertThat(r.droppedMessages()).isZero();
    }

    @Test
    void unchangedSaysWhy() {
        List<Message> m = new Convo().system("s").task("t").build();
        CompactionResult r =
                CompactionResult.unchanged(
                        "x", m, req, CompactionResult.Outcome.SKIPPED, "already within the target");
        assertThat(r.messages()).isEqualTo(m);
        assertThat(r.note()).isEqualTo("already within the target");
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
    }

    @Test
    void theResultHoldsACopyOfTheMessages() {
        List<Message> before = new Convo().system("s").task("t").step("a", 3_000).build();
        List<Message> after = new ArrayList<>(before.subList(0, 2));
        CompactionResult r = CompactionResult.of("x", before, after, req);
        after.add(Message.user("late"));
        assertThat(r.messages()).hasSize(2);
    }

    @Test
    void usageAndNotesCanBeAttachedWithoutChangingTheRest() {
        List<Message> m = new Convo().system("s").task("t").step("a", 3_000).build();
        CompactionResult r = CompactionResult.of("x", m, m.subList(0, 2), req);
        CompactionUsage u = new CompactionUsage(1, 500, 80, new java.math.BigDecimal("0.002"));
        CompactionResult withUsage = r.withUsage(u).withNote("fell back");
        assertThat(withUsage.summarizerUsage()).isEqualTo(u);
        assertThat(withUsage.note()).isEqualTo("fell back");
        assertThat(withUsage.messages()).isEqualTo(r.messages());
        assertThat(withUsage.tokensAfter()).isEqualTo(r.tokensAfter());
    }
}
