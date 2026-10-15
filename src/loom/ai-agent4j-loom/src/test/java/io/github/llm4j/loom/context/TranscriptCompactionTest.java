package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.context.ContextCompactors;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.memory.TranscriptAccumulationEngine;
import io.github.llm4j.loom.runtime.DefaultVariableContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R13: Loom's transcript memory stays bounded, keeps pairs and the latest entries, and a rewind still restores it. */
class TranscriptCompactionTest {

    private static AgentDef agent(String name) {
        AgentDef a = new AgentDef(name);
        return a;
    }

    private static AgentResult result(String answer) {
        return AgentResult.builder().finalAnswer(answer).build();
    }

    private static TranscriptAccumulationEngine bounded(long maxTokens) {
        return new TranscriptAccumulationEngine().limits(a -> new TranscriptAccumulationEngine.Limit(maxTokens, ContextCompactors.dropOldest(), 0.5, 2));
    }

    private static void turn(TranscriptAccumulationEngine e, AgentDef a, int i) {
        e.storeOutcome(a, "task " + i + " " + "t".repeat(100), result("answer " + i + " " + "r".repeat(300)), new DefaultVariableContext());
    }

    private static String briefing(TranscriptAccumulationEngine e, AgentDef a) {
        return e.assembleContext(a, "now", new DefaultVariableContext());
    }

    @Test
    void withoutALimitTheTranscriptGrowsAsBefore() {
        TranscriptAccumulationEngine e = new TranscriptAccumulationEngine();
        AgentDef a = agent("A");
        for (int i = 0; i < 30; i++) turn(e, a, i);
        String b = briefing(e, a);
        assertThat(b).contains("task 0 ").contains("answer 29 ").startsWith("Conversation History:\n");
        assertThat(b).doesNotContain("Earlier");
    }

    @Test
    void theBriefingStaysUnderTheLimitWhileTheTranscriptIsLong() {
        TranscriptAccumulationEngine e = bounded(600);
        AgentDef a = agent("A");
        for (int i = 0; i < 30; i++) {
            turn(e, a, i);
            assertThat(briefing(e, a).length() / 4).as("after turn " + i).isLessThanOrEqualTo(600 + 50);
        }
    }

    @Test
    void theLatestEntriesSurviveAndAnExchangeIsNeverSplit() {
        TranscriptAccumulationEngine e = bounded(600);
        AgentDef a = agent("A");
        for (int i = 0; i < 30; i++) turn(e, a, i);
        String b = briefing(e, a);
        assertThat(b).contains("task 29 ").contains("answer 29 ").contains("task 28 ").contains("answer 28 ");
        for (int i = 0; i < 30; i++) {
            boolean task = b.contains("task " + i + " ");
            boolean answer = b.contains("answer " + i + " ");
            assertThat(task).as("exchange " + i + " is whole").isEqualTo(answer);
        }
        assertThat(b).doesNotContain("task 0 ");
    }

    @Test
    void whatWasDroppedIsMarked() {
        TranscriptAccumulationEngine e = bounded(600);
        AgentDef a = agent("A");
        for (int i = 0; i < 30; i++) turn(e, a, i);
        assertThat(briefing(e, a)).containsIgnoringCase("earlier");
    }

    @Test
    void aStrategyThatThrowsOrGrowsLeavesTheTranscriptAlone() {
        AgentDef a = agent("A");
        TranscriptAccumulationEngine thrower = new TranscriptAccumulationEngine().limits(x -> new TranscriptAccumulationEngine.Limit(100, (m, r) -> { throw new IllegalStateException("boom"); }, 0.5, 2));
        for (int i = 0; i < 5; i++) turn(thrower, a, i);
        assertThat(briefing(thrower, a)).contains("task 0 ").contains("answer 4 ");
    }

    @Test
    void aRewindRestoresTheMarkedStateEvenAcrossCompactions() {
        TranscriptAccumulationEngine e = bounded(600);
        AgentDef a = agent("A");
        for (int i = 0; i < 6; i++) turn(e, a, i);
        Object mark = e.mark();
        String atMark = briefing(e, a);
        for (int i = 6; i < 40; i++) turn(e, a, i);
        assertThat(briefing(e, a)).isNotEqualTo(atMark);
        e.restore(mark);
        assertThat(briefing(e, a)).isEqualTo(atMark);
        turn(e, a, 99);
        assertThat(briefing(e, a)).contains("answer 99 ");
    }

    @Test
    void anOldStyleMarkStillWorks() {
        TranscriptAccumulationEngine e = new TranscriptAccumulationEngine();
        AgentDef a = agent("A");
        turn(e, a, 1);
        Object mark = e.mark();
        turn(e, a, 2);
        e.restore(mark);
        assertThat(briefing(e, a)).contains("task 1 ").doesNotContain("task 2 ");
    }

    @Test
    void agentsWithoutALimitDoNotTriggerCompactionOfTheSharedTranscript() {
        TranscriptAccumulationEngine e = new TranscriptAccumulationEngine().limits(x -> x.getName().equals("Bounded") ? new TranscriptAccumulationEngine.Limit(600, ContextCompactors.dropOldest(), 0.5, 2) : null);
        AgentDef free = agent("Free");
        for (int i = 0; i < 20; i++) turn(e, free, i);
        assertThat(briefing(e, free)).contains("task 0 ");
        AgentDef bounded = agent("Bounded");
        turn(e, bounded, 20);
        assertThat(briefing(e, bounded).length() / 4).isLessThanOrEqualTo(650);
    }

    // ── through a script ──────────────────────────────────────────────────────────────

    private static String twoAgents(String memoryBlock, String contextBlock) {
        return "agent Writer { model: \"tiny-model\"  " + memoryBlock + " " + contextBlock + " }\n"
                + "workflow Main() {\n"
                + "  delegate \"write a long paragraph about topic\" to Writer -> out\n".repeat(12)
                + "}\n";
    }

    private static List<Integer> sizes(TestLoom l) {
        List<Integer> out = new java.util.ArrayList<>();
        synchronized (l.requests) {
            for (var r : l.requests) out.add(r.getMessages().get(r.getMessages().size() - 1).getContent().length());
        }
        return out;
    }

    private static int largestBriefing(TestLoom l) {
        int max = 0;
        synchronized (l.requests) {
            for (var r : l.requests) {
                String last = r.getMessages().get(r.getMessages().size() - 1).getContent();
                max = Math.max(max, last.length());
            }
        }
        return max;
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    private TestLoom scripted(String src) {
        TestLoom l = new TestLoom(dir, src, 0);
        l.answerWith = "z".repeat(900);
        return l;
    }

    @Test
    void aMemoryBlockWithMaxTokensBoundsWhatTheNextAgentIsGiven() {
        TestLoom bounded = scripted(twoAgents("memory { max_tokens: 700  compact: drop_oldest }", ""));
        bounded.run();
        TestLoom free = scripted(twoAgents("", ""));
        free.run();
        assertThat(largestBriefing(free)).isGreaterThan(8000);
        assertThat(largestBriefing(bounded)).as("bounded: the briefing stops growing").isLessThan(largestBriefing(free) / 2 + 500);
        assertThat(sizes(bounded).subList(6, 12)).as("a steady size once the bound is reached").containsOnly(sizes(bounded).get(11));
    }

    @Test
    void anAgentWithAContextBlockAndAKnownWindowBoundsItToAQuarterOfTheUsableInput() {
        TestLoom l = scripted(twoAgents("", "context { compact: drop_oldest }"));
        l.executor.setContextWindows(WindowLayers.none().over("t", io.github.llm4j.context.ContextWindows.empty().with("tiny-model", new io.github.llm4j.context.ContextWindow(4000, 1000))));
        l.run();
        assertThat(sizes(l).subList(6, 12)).as("a steady size once the bound is reached").containsOnly(sizes(l).get(11));
        assertThat(largestBriefing(l)).isLessThan(6000);
    }

    @Test
    void withoutEitherTheTranscriptIsUnboundedAsBefore() {
        TestLoom l = scripted(twoAgents("", "context { compact: drop_oldest }"));
        l.executor.setContextWindows(WindowLayers.none());
        l.run();
        assertThat(largestBriefing(l)).isGreaterThan(8000);
    }
}
