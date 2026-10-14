package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrimToolResultsTest {

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).build();
    }

    private static List<Message> convo() {
        return new Convo().system("sys").task("task").step("fetch", 6_000).step("fetch", 6_000).step("fetch", 6_000)
                .step("fetch", 6_000).step("fetch", 6_000).build();
    }

    @Test
    void shortensTheOldestLargeResultsFirstKeepingTheirHeadAndTail() {
        List<Message> in = convo();
        ContextCompactor trim = ContextCompactors.trimToolResults(1_000);
        long size = req(1).sizeOf(in);
        CompactionResult r = trim.compact(in, req(size - 2_000)); // needs about two results trimmed

        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        List<Message> out = r.messages();
        assertThat(out).hasSameSizeAs(in);
        Message first = out.get(3); // the oldest result
        assertThat(first.getContent().length()).isLessThan(in.get(3).getContent().length());
        assertThat(first.getContent()).startsWith(in.get(3).getContent().substring(0, 100));
        assertThat(first.getContent()).endsWith(in.get(3).getContent().substring(in.get(3).getContent().length() - 100));
        assertThat(first.getContent()).contains("characters omitted");
        assertThat(first.getToolCallId()).isEqualTo(in.get(3).getToolCallId());
        assertThat(Messages.compactionOf(first)).contains("trim_tool_results");
        assertThat(req(1).sizeOf(out)).isLessThanOrEqualTo(size - 2_000);
        // the newest results are untouched because they are not needed to reach the target
        assertThat(out.get(out.size() - 1)).isSameAs(in.get(in.size() - 1));
    }

    @Test
    void stopsAsSoonAsTheTargetIsReached() {
        List<Message> in = convo();
        ContextCompactor trim = ContextCompactors.trimToolResults(1_000);
        long size = req(1).sizeOf(in);
        CompactionResult r = trim.compact(in, req(size - 1_000));
        long changed = java.util.stream.IntStream.range(0, in.size()).filter(i -> r.messages().get(i) != in.get(i)).count();
        assertThat(changed).isEqualTo(1);
    }

    @Test
    void leavesSmallResultsAndNonToolMessagesAlone() {
        List<Message> in = new Convo().system("sys").task("t".repeat(10_000)).step("a", 200).step("b", 200).step("c", 200).answer("x".repeat(9_000)).build();
        CompactionResult r = ContextCompactors.trimToolResults(1_000).compact(in, req(1));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.messages()).isEqualTo(in);
    }

    @Test
    void doesNotTrimWhatWouldNotGetSmaller() {
        // a result only slightly over the limit: the marker would make it longer, so it is left as it is
        List<Message> in = new Convo().system("sys").task("task").step("a", 1_020).step("b", 100).step("c", 100).build();
        CompactionResult r = ContextCompactors.trimToolResults(1_000).compact(in, req(1));
        assertThat(r.messages().get(3)).isSameAs(in.get(3));
    }

    @Test
    void aSecondPassDoesNotTrimAgain() {
        List<Message> in = convo();
        ContextCompactor trim = ContextCompactors.trimToolResults(1_000);
        CompactionResult first = trim.compact(in, req(1));
        CompactionResult second = trim.compact(first.messages(), req(1));
        assertThat(second.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
    }

    @Test
    void theLimitMustBeUseful() {
        assertThatThrownBy(() -> ContextCompactors.trimToolResults(0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxChars");
        assertThatThrownBy(() -> ContextCompactors.trimToolResults(39)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least");
    }
}
