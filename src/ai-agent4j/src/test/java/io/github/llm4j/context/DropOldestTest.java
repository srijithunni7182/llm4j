package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.model.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DropOldestTest {

    private final ContextCompactor drop = ContextCompactors.dropOldest();

    private static CompactionRequest req(long target) {
        return CompactionRequest.builder(target).keepRecent(2).pinned(Messages::isPinned).build();
    }

    private static List<Message> ten() {
        Convo c = new Convo().system("sys").task("task");
        for (int i = 0; i < 10; i++) c.step("search", 400);
        return c.build();
    }

    @Test
    void removesTheOldestExchangesAndLeavesOneMarker() {
        List<Message> in = ten();
        long size = req(1).sizeOf(in);
        CompactionResult r = drop.compact(in, req(size / 2));

        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
        assertThat(r.strategy()).isEqualTo("drop_oldest");
        List<Message> out = r.messages();
        assertThat(out.get(0)).isSameAs(in.get(0)); // system
        assertThat(out.get(1)).isSameAs(in.get(1)); // task
        assertThat(out.get(2).getContent()).contains("earlier messages were removed");
        assertThat(Messages.compactionOf(out.get(2))).contains("drop_oldest");
        assertThat(out.subList(out.size() - 2, out.size()))
                .containsExactlyElementsOf(in.subList(in.size() - 2, in.size()));
        assertThat(req(1).sizeOf(out)).isLessThanOrEqualTo(size / 2);
        // what was removed is the oldest part: the first surviving exchange is later than the first
        // original one
        assertThat(out.get(3)).isNotEqualTo(in.get(2));
        assertThat(r.droppedMessages()).isEqualTo(in.size() - (out.size() - 1));
        assertThat(Messages.dropped(out.get(2))).isEqualTo(r.droppedMessages());
    }

    @Test
    void removesNoMoreThanItNeeds() {
        List<Message> in = ten();
        long size = req(1).sizeOf(in);
        long target = size - 50; // one exchange is enough
        CompactionResult r = drop.compact(in, req(target));
        assertThat(r.droppedMessages()).isEqualTo(2); // one assistant message and its one result
        assertThat(req(1).sizeOf(r.messages())).isLessThanOrEqualTo(target);
    }

    @Test
    void doesNothingWhenAlreadyWithinTheTarget() {
        List<Message> in = ten();
        CompactionResult r = drop.compact(in, req(req(1).sizeOf(in)));
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
        assertThat(r.messages()).isEqualTo(in);
    }

    @Test
    void aMarkerFromAnEarlierPassIsMergedNotNested() {
        List<Message> in = ten();
        long size = req(1).sizeOf(in);
        CompactionResult first = drop.compact(in, req(size * 6 / 10));
        int firstDropped = first.droppedMessages();
        CompactionResult second = drop.compact(first.messages(), req(size * 3 / 10));

        List<Message> out = second.messages();
        long markers = out.stream().filter(m -> Messages.compactionOf(m).isPresent()).count();
        assertThat(markers).isEqualTo(1);
        Message marker =
                out.stream()
                        .filter(m -> Messages.compactionOf(m).isPresent())
                        .findFirst()
                        .orElseThrow();
        assertThat(Messages.dropped(marker)).isGreaterThan(firstDropped);
        assertThat(marker.getContent()).contains(String.valueOf(Messages.dropped(marker)));
    }

    @Test
    void aPinnedMessageSurvivesAndItsNeighboursAreStillRemovable() {
        Message pinned =
                Message.builder()
                        .role(Message.Role.USER)
                        .content("Budget is 500.")
                        .providerData(Map.of(Messages.PINNED, true))
                        .build();
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .step("a", 400)
                        .add(pinned)
                        .step("b", 400)
                        .step("c", 400)
                        .step("d", 400)
                        .step("e", 400)
                        .build();
        CompactionResult r = drop.compact(in, req(req(1).sizeOf(in) / 3));
        assertThat(r.messages()).contains(pinned);
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.COMPACTED);
    }

    @Test
    void anExchangeHoldingAPinnedResultIsKeptWhole() {
        Message result =
                Message.builder()
                        .role(Message.Role.TOOL)
                        .name("a")
                        .toolCallId("keep")
                        .content("important")
                        .providerData(Map.of(Messages.PINNED, true))
                        .build();
        Message call =
                Message.assistantToolCalls(
                        "t",
                        List.of(new io.github.llm4j.model.ToolCall("keep", "a", Map.of())),
                        Map.of());
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .add(call)
                        .add(result)
                        .step("b", 500)
                        .step("c", 500)
                        .step("d", 500)
                        .step("e", 500)
                        .build();
        CompactionResult r = drop.compact(in, req(1));
        assertThat(r.messages()).contains(call, result);
    }

    @Test
    void theBoundaryOfTheRecentWindowNeverSplitsAnExchange() {
        // keepRecent 2 would start the tail at the second result of a parallel turn: the whole turn
        // is kept
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .step("a", 500)
                        .step("b", 500)
                        .parallel(2, 500, "p")
                        .build();
        CompactionResult r = drop.compact(in, req(1));
        List<Message> out = r.messages();
        assertThat(out.subList(out.size() - 3, out.size()))
                .containsExactlyElementsOf(in.subList(in.size() - 3, in.size()));
    }

    @Test
    void parallelCallsAreRemovedTogether() {
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .parallel(4, 800, "p")
                        .step("a", 800)
                        .step("b", 800)
                        .build();
        CompactionResult r = drop.compact(in, req(req(1).sizeOf(in) - 100));
        long toolsLeft =
                r.messages().stream().filter(m -> m.getRole() == Message.Role.TOOL).count();
        long callsLeft = r.messages().stream().mapToLong(m -> m.getToolCalls().size()).sum();
        assertThat(toolsLeft).isEqualTo(callsLeft);
    }

    @Test
    void anOrphanToolResultIsRemovedOnItsOwn() {
        Message orphan = Message.toolResult("nobody", "x", "x".repeat(2_000));
        List<Message> in =
                new Convo()
                        .system("sys")
                        .task("task")
                        .add(orphan)
                        .step("a", 100)
                        .step("b", 100)
                        .build();
        CompactionResult r = drop.compact(in, req(req(1).sizeOf(in) - 200));
        assertThat(r.messages()).doesNotContain(orphan);
    }

    @Test
    void whenNothingCanBeRemovedItSaysSo() {
        List<Message> in = new Convo().system("sys").task("a task").step("a", 5_000).build();
        CompactionResult r = drop.compact(in, CompactionRequest.builder(1).keepRecent(10).build());
        assertThat(r.outcome()).isEqualTo(CompactionResult.Outcome.NO_GAIN);
        assertThat(r.messages()).isEqualTo(in);
    }

    @Test
    void reportsTheNumbers() {
        List<Message> in = ten();
        CompactionRequest req = req(req(1).sizeOf(in) / 2);
        CompactionResult r = drop.compact(in, req);
        assertThat(r.messagesBefore()).isEqualTo(in.size());
        assertThat(r.messagesAfter()).isEqualTo(r.messages().size());
        assertThat(r.tokensBefore()).isEqualTo(req.sizeOf(in));
        assertThat(r.tokensAfter()).isEqualTo(req.sizeOf(r.messages()));
        assertThat(r.summarizerUsage()).isNull();
    }

    @Test
    void theFirstUserMessageCanGoWhenItIsNotTheTask() {
        List<Message> in =
                new Convo()
                        .task("hello")
                        .answer("hi " + "x".repeat(400))
                        .task("and?")
                        .answer("more " + "y".repeat(400))
                        .task("ok")
                        .answer("done")
                        .build();
        CompactionRequest req =
                CompactionRequest.builder(30).keepRecent(2).protectTask(false).build();
        CompactionResult r = drop.compact(in, req);
        assertThat(r.messages()).doesNotContain(in.get(0));
        assertThat(r.messages().subList(r.messages().size() - 2, r.messages().size()))
                .containsExactlyElementsOf(in.subList(in.size() - 2, in.size()));
        // and with the task protected (the default) the first message stays
        assertThat(drop.compact(in, CompactionRequest.builder(30).keepRecent(2).build()).messages())
                .contains(in.get(0));
    }
}
