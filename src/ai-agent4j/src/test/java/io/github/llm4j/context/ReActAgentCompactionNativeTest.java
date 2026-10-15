package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.budget.CharsPerTokenEstimator;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Compaction in the native tool-calling loop (design §4.4, §4.5): R5–R11, R21. */
class ReActAgentCompactionNativeTest {

    static final int WINDOW = 4_000;

    /** A model that calls the search tool {@code steps} times, with a different query each time, then answers. */
    static ScriptedModel looping(int steps) {
        return ScriptedModel.by(true, true, n -> n < steps
                ? ScriptedModel.calls("step " + n, ScriptedModel.call("c" + n, "search", Map.of("q", "query " + n)))
                : ScriptedModel.answer("done after " + steps));
    }

    static ScriptedModel.FixedTool search(int chars) {
        return new ScriptedModel.FixedTool("search", n -> ("result " + n + " ").repeat(1).concat("x".repeat(chars)));
    }

    static ReActAgent.Builder agent(ScriptedModel model, ScriptedModel.FixedTool tool) {
        return ReActAgent.builder().llmClient(model).addTool(tool).maxIterations(500);
    }

    static ContextPolicy policy() {
        return ContextPolicy.builder().window(ContextWindow.of(WINDOW)).triggerAt(0.8).targetAt(0.5).keepRecent(2).build();
    }

    static long size(LLMRequest r) {
        return CharsPerTokenEstimator.INSTANCE.prompt(r);
    }

    /** Every tool message has its call earlier in the same request, and every call has its result (R8). */
    static void assertPairsWhole(LLMRequest r) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (Message m : r.getMessages()) {
            for (ToolCall c : m.getToolCalls()) calls.add(c.id());
            if (m.getRole() == Message.Role.TOOL) {
                assertThat(calls).as("the call comes before its result").contains(m.getToolCallId());
                results.add(m.getToolCallId());
            }
        }
        int last = r.getMessages().size() - 1;
        for (int i = 0; i <= last; i++) {
            for (ToolCall c : r.getMessages().get(i).getToolCalls()) assertThat(results).as("call " + c.id() + " has its result").contains(c.id());
        }
    }

    @Test
    void keepsEveryRequestWithinTheWindow() {
        ScriptedModel model = looping(40);
        ContextListener listener = new ContextListener();
        AgentResult r = agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).addListener(listener).build().run("Find everything.");

        assertThat(r.getFinalAnswer()).isEqualTo("done after 40");
        assertThat(model.requests).hasSize(41);
        assertThat(listener.compacted()).isGreaterThan(2);
        for (LLMRequest q : model.requests) assertThat(size(q)).as("a request is within the window").isLessThanOrEqualTo(WINDOW);
        // without compaction the last request would have been far over it
        assertThat(40 * 340L).isGreaterThan(WINDOW);
    }

    @Test
    void theInstructionsAndTheTaskAreInEveryRequest() {
        ScriptedModel model = looping(30);
        agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).build().run("Find everything.");
        for (LLMRequest q : model.requests) {
            assertThat(q.getMessages().get(0).getRole()).isEqualTo(Message.Role.SYSTEM);
            assertThat(q.getMessages().get(1).getContent()).isEqualTo("Find everything.");
        }
    }

    @Test
    void aToolCallIsNeverSeparatedFromItsResult() {
        ScriptedModel model = looping(30);
        agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).build().run("Q?");
        model.requests.forEach(ReActAgentCompactionNativeTest::assertPairsWhole);
    }

    @Test
    void theSavingLastsForTheRestOfTheRun() {
        ScriptedModel model = looping(30);
        ContextListener listener = new ContextListener();
        agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).addListener(listener).build().run("Q?");
        CompactionEvent first = listener.compactions.get(0);
        int at = first.iteration();
        // the request made right after the compaction is smaller than the one before it, and carries on from there
        assertThat(size(model.requests.get(at))).isLessThan(size(model.requests.get(at - 1)));
        assertThat(model.requests.get(at).getMessages().size()).isLessThan(model.requests.get(at - 1).getMessages().size());
        // and it is built on the compacted conversation: the removal marker is still there many requests later
        boolean markerLater = model.requests.get(model.requests.size() - 1).getMessages().stream().anyMatch(m -> Messages.compactionOf(m).isPresent());
        assertThat(markerLater).isTrue();
    }

    @Test
    void aPinnedResultSurvivesEveryCompaction() {
        ScriptedModel model = ScriptedModel.by(true, true, n -> n < 30
                ? ScriptedModel.calls("s", ScriptedModel.call("c" + n, "search", Map.of("q", "query " + n)))
                : ScriptedModel.answer("done"));
        ScriptedModel.FixedTool tool = new ScriptedModel.FixedTool("search", n -> n == 2 ? "KEEP-ME the budget is 500" : "x".repeat(1_200));
        ContextPolicy p = ContextPolicy.builder().window(ContextWindow.of(WINDOW)).keepRecent(2).pinned(m -> m.getContent().contains("KEEP-ME")).build();
        agent(model, tool).contextPolicy(p).compaction(ContextCompactors.dropOldest()).build().run("Q?");
        LLMRequest last = model.requests.get(model.requests.size() - 1);
        assertThat(last.getMessages().stream().anyMatch(m -> m.getContent().contains("KEEP-ME"))).isTrue();
        assertPairsWhole(last);
    }

    @Test
    void aCompactorThatDoesNothingIsStoppedAndTheRunStillFinishes() {
        ScriptedModel model = looping(12);
        ContextListener listener = new ContextListener();
        ContextCompactor nothing = (m, q) -> CompactionResult.of("nothing", m, m, q);
        AgentResult r = agent(model, search(1_200)).contextPolicy(policy()).compaction(nothing).addListener(listener).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 12");
        assertThat(listener.compactions).hasSize(3);
        assertThat(listener.compactions.get(2).result().outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
    }

    @Test
    void aCompactorThatThrowsDoesNotFailTheRun() {
        ScriptedModel model = looping(12);
        ContextListener listener = new ContextListener();
        ContextCompactor boom = (m, q) -> {
            throw new IllegalStateException("bang");
        };
        AgentResult r = agent(model, search(1_200)).contextPolicy(policy()).compaction(boom).addListener(listener).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 12");
        assertThat(listener.compactions).isNotEmpty();
        assertThat(listener.compactions.get(0).failure()).contains("bang");
    }

    @Test
    void aListenerThatThrowsDoesNotFailTheRun() {
        ScriptedModel model = looping(20);
        AgentEventListener bad = new AgentEventListener() {
            @Override public void onThought(String t) {}
            @Override public void onAction(String a, String i) {}
            @Override public void onObservation(String o) {}
            @Override public void onContextUsage(ContextUsage u) { throw new IllegalStateException("listener"); }
            @Override public void onCompaction(CompactionEvent e) { throw new IllegalStateException("listener"); }
        };
        AgentResult r = agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).addListener(bad).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 20");
    }

    // ── usage events (R5) ──────────────────────────────────────────────────────────

    @Test
    void usageIsReportedBeforeEveryModelCall() {
        ScriptedModel model = looping(5);
        ContextListener listener = new ContextListener();
        agent(model, search(400)).contextPolicy(policy()).addListener(listener).build().run("Q?");
        assertThat(listener.usages).hasSize(model.requests.size());
        assertThat(listener.usages.get(0).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
        for (int i = 1; i < listener.usages.size(); i++) assertThat(listener.usages.get(i).source()).isEqualTo(ContextUsage.Source.REPORTED);
        assertThat(listener.usages.get(0).window().tokens()).isEqualTo(WINDOW);
        assertThat(listener.usages.get(0).fraction()).isPresent();
        for (int i = 1; i < listener.usages.size(); i++) assertThat(listener.usages.get(i).used()).isGreaterThan(listener.usages.get(i - 1).used());
        assertThat(listener.compactions).isEmpty(); // no compactor was given
    }

    @Test
    void theFirstEstimateCountsTheToolDefinitions() {
        ScriptedModel model = looping(1);
        ContextListener listener = new ContextListener();
        agent(model, search(400)).contextPolicy(policy()).addListener(listener).build().run("Q?");
        // the first usage is an estimate of the first request, tool definitions included
        long sent = size(model.requests.get(0));
        long messagesOnly = CompactionRequest.of(1).sizeOf(model.requests.get(0).getMessages());
        assertThat(sent).isGreaterThan(messagesOnly); // the tools do add to the prompt
        assertThat(listener.usages.get(0).used()).isBetween(sent - 2, sent + 2); // two separately rounded estimates may differ by a token
    }

    @Test
    void usageIsReportedWithoutAWindowToo() {
        ScriptedModel model = looping(3);
        ContextListener listener = new ContextListener();
        agent(model, search(400)).addListener(listener).build().run("Q?");
        assertThat(listener.usages).hasSize(model.requests.size());
        assertThat(listener.usages.get(0).fraction()).isEmpty();
    }

    @Test
    void theWindowComesFromTheCatalogueByTheModelNameTheAgentAlreadyHas() {
        ScriptedModel model = looping(2);
        ContextListener listener = new ContextListener();
        ContextWindows windows = ContextWindows.empty().with("acme/big", ContextWindow.of(9_000).reservingForOutput(1_000));
        agent(model, search(400)).budgetModel("acme/big").contextWindows(windows).addListener(listener).build().run("Q?");
        assertThat(listener.usages.get(0).window().usableInput()).isEqualTo(8_000);
    }

    @Test
    void anExplicitWindowIsShorthandForOneInThePolicy() {
        ScriptedModel model = looping(2);
        ContextListener listener = new ContextListener();
        agent(model, search(400)).contextWindow(6_000).addListener(listener).build().run("Q?");
        assertThat(listener.usages.get(0).window().tokens()).isEqualTo(6_000);
    }

    @Test
    void aWindowInThePolicyWinsOverTheShorthandAndTheCatalogue() {
        ScriptedModel model = looping(1);
        ContextListener listener = new ContextListener();
        ContextWindows windows = ContextWindows.empty().with("acme/big", ContextWindow.of(9_000));
        agent(model, search(400)).contextPolicy(policy()).contextWindow(6_000).budgetModel("acme/big").contextWindows(windows)
                .addListener(listener).build().run("Q?");
        assertThat(listener.usages.get(0).window().tokens()).isEqualTo(WINDOW);
    }

    @Test
    void contextSettingsSurviveToBuilder() {
        ScriptedModel model = looping(30);
        ContextListener listener = new ContextListener();
        ReActAgent original = agent(model, search(1_200)).contextPolicy(policy()).compaction(ContextCompactors.dropOldest()).build();
        original.toBuilder().addListener(listener).build().run("Q?");
        assertThat(listener.compacted()).isGreaterThan(0);
    }

    @Test
    void withNothingConfiguredThereIsNoOverheadAndNoEvents() {
        ScriptedModel model = looping(3);
        AgentResult r = agent(model, search(400)).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 3");
    }
}
