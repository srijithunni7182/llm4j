package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compaction in the text-protocol loop (design §4.5): R5 to R8, R17, R18. */
class ReActAgentCompactionTextTest {

    static final int WINDOW = 4_000;

    /** A text-protocol model that calls the tool {@code steps} times with a different query each time, then answers. */
    static ScriptedModel looping(int steps) {
        return ScriptedModel.by(false, true, n -> n < steps
                ? ScriptedModel.textStep("thought " + n, "search", "{\"q\":\"query " + n + "\"}")
                : ScriptedModel.textAnswer("done after " + steps));
    }

    static ReActAgent.Builder agent(ScriptedModel model, int resultChars) {
        return ReActAgent.builder().llmClient(model).addTool(ReActAgentCompactionNativeTest.search(resultChars)).maxIterations(1_000);
    }

    static String prompt(LLMRequest r) {
        return r.getMessages().get(r.getMessages().size() - 1).getContent();
    }

    @Test
    void keepsEveryRequestWithinTheWindow() {
        ScriptedModel model = looping(40);
        ContextListener listener = new ContextListener();
        AgentResult r = agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest())
                .addListener(listener).build().run("Find everything.");

        assertThat(r.getFinalAnswer()).isEqualTo("done after 40");
        assertThat(model.requests).hasSize(41);
        assertThat(listener.compacted()).isGreaterThan(2);
        for (LLMRequest q : model.requests) assertThat(ReActAgentCompactionNativeTest.size(q)).isLessThanOrEqualTo(WINDOW);
    }

    @Test
    void theQuestionAndTheLatestStepsAreAlwaysThere() {
        ScriptedModel model = looping(30);
        agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest()).build().run("Find everything.");
        for (int i = 0; i < model.requests.size(); i++) {
            String p = prompt(model.requests.get(i));
            assertThat(p).as("request " + i).startsWith("Question: Find everything.\n");
            if (i > 0) assertThat(p).as("request " + i + " still holds the latest step").contains("Action Input: {\"q\":\"query " + (i - 1) + "\"}");
        }
    }

    @Test
    void afterACompactionTheScratchpadSaysEarlierStepsWereRemoved() {
        ScriptedModel model = looping(30);
        ContextListener listener = new ContextListener();
        agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest()).addListener(listener).build().run("Q?");
        int at = listener.compactions.get(0).iteration();
        assertThat(prompt(model.requests.get(at - 1))).doesNotContain("Earlier steps");
        assertThat(prompt(model.requests.get(at))).contains("Earlier steps: [").contains("earlier messages were removed to save space.]");
        assertThat(prompt(model.requests.get(at))).doesNotContain("Action Input: {\"q\":\"query 0\"}");
        assertThat(prompt(model.requests.get(model.requests.size() - 1))).contains("Earlier steps: [");
    }

    @Test
    void theSavingLastsForTheRestOfTheRun() {
        ScriptedModel model = looping(30);
        ContextListener listener = new ContextListener();
        agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest()).addListener(listener).build().run("Q?");
        int at = listener.compactions.get(0).iteration();
        assertThat(prompt(model.requests.get(at)).length()).isLessThan(prompt(model.requests.get(at - 1)).length());
    }

    @Test
    void largeObservationsAreShortenedInPlace() {
        ScriptedModel model = looping(12);
        ContextListener listener = new ContextListener();
        ContextPolicy policy = ContextPolicy.builder().window(ContextWindow.of(WINDOW)).keepRecent(2).build();
        AgentResult r = agent(model, 1_200).contextPolicy(policy).compaction(ContextCompactors.trimToolResults(300)).addListener(listener).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 12");
        String last = prompt(model.requests.get(model.requests.size() - 1));
        assertThat(last).contains("characters omitted");
        assertThat(last).contains("Action Input: {\"q\":\"query 0\"}"); // the early steps are still listed, only their results are shorter
        assertThat(listener.compacted()).isGreaterThan(0);
    }

    @Test
    void aPinnedObservationSurvives() {
        ScriptedModel model = looping(30);
        ScriptedModel.FixedTool tool = new ScriptedModel.FixedTool("search", n -> n == 2 ? "KEEP-ME the budget is 500" : "x".repeat(1_200));
        ContextPolicy p = ContextPolicy.builder().window(ContextWindow.of(WINDOW)).keepRecent(2).pinned(m -> m.getContent().contains("KEEP-ME")).build();
        ReActAgent.builder().llmClient(model).addTool(tool).maxIterations(1_000).contextPolicy(p).compaction(ContextCompactors.dropOldest()).build().run("Q?");
        String last = prompt(model.requests.get(model.requests.size() - 1));
        assertThat(last).contains("KEEP-ME the budget is 500");
        assertThat(last).contains("Earlier steps: ["); // and others were removed around it
    }

    @Test
    void theRunReachesTheSameAnswerWithAndWithoutCompaction() {
        AgentResult plain = agent(looping(25), 1_200).build().run("Q?");
        AgentResult compacted = agent(looping(25), 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy())
                .compaction(ContextCompactors.dropOldest()).build().run("Q?");
        assertThat(compacted.getFinalAnswer()).isEqualTo(plain.getFinalAnswer());
        assertThat(compacted.getSteps()).hasSameSizeAs(plain.getSteps());
    }

    @Test
    void usageIsReportedBeforeEveryModelCall() {
        ScriptedModel model = looping(6);
        ContextListener listener = new ContextListener();
        agent(model, 400).contextPolicy(ReActAgentCompactionNativeTest.policy()).addListener(listener).build().run("Q?");
        assertThat(listener.usages).hasSize(model.requests.size());
        assertThat(listener.usages.get(0).source()).isEqualTo(ContextUsage.Source.ESTIMATED);
        for (int i = 1; i < listener.usages.size(); i++) {
            assertThat(listener.usages.get(i).source()).isEqualTo(ContextUsage.Source.REPORTED);
            assertThat(listener.usages.get(i).used()).isGreaterThan(listener.usages.get(i - 1).used());
        }
        assertThat(listener.usages.get(0).window().tokens()).isEqualTo(WINDOW);
        assertThat(listener.compactions).isEmpty();
    }

    @Test
    void theFirstEstimateCountsTheSystemPromptAndTheScratchpad() {
        ScriptedModel model = looping(1);
        ContextListener listener = new ContextListener();
        agent(model, 400).contextPolicy(ReActAgentCompactionNativeTest.policy()).addListener(listener).build().run("Q?");
        long sent = ReActAgentCompactionNativeTest.size(model.requests.get(0));
        assertThat(listener.usages.get(0).used()).isBetween(sent - 3, sent + 3);
        assertThat(listener.usages.get(0).used()).isGreaterThan(CompactionRequest.of(1).sizeOf(List.of(io.github.llm4j.model.Message.user("Question: Q?\n"))));
    }

    @Test
    void aCompactorThatThrowsDoesNotFailTheRun() {
        ScriptedModel model = looping(20);
        ContextListener listener = new ContextListener();
        ContextCompactor boom = (m, q) -> {
            throw new IllegalStateException("bang");
        };
        AgentResult r = agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(boom).addListener(listener).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done after 20");
        assertThat(listener.compactions.get(0).failure()).contains("bang");
    }

    @Test
    void aCompactorThatDoesNothingIsStopped() {
        ScriptedModel model = looping(30);
        ContextListener listener = new ContextListener();
        ContextCompactor nothing = (m, q) -> CompactionResult.of("nothing", m, m, q);
        agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(nothing).addListener(listener).build().run("Q?");
        assertThat(listener.compactions).hasSize(3);
        assertThat(listener.compactions.get(2).result().outcome()).isEqualTo(CompactionResult.Outcome.SKIPPED);
    }

    @Test
    void turnsWithNoValidActionAreHandledAndCompactable() {
        // alternate a step with a reply that names no action, so the scratchpad holds both kinds of turn
        ScriptedModel model = ScriptedModel.by(false, true, n -> n >= 30 ? ScriptedModel.textAnswer("done")
                : n % 3 == 2 ? ScriptedModel.answer("```json\n{\"thought\": \"" + "thinking ".repeat(100) + n + "\"}\n```")
                : ScriptedModel.textStep("thought " + n, "search", "{\"q\":\"query " + n + "\"}"));
        ContextListener listener = new ContextListener();
        AgentResult r = agent(model, 1_200).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest())
                .addListener(listener).build().run("Q?");
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(listener.compacted()).isGreaterThan(0);
        assertThat(prompt(model.requests.get(model.requests.size() - 1))).contains("Observation: No valid action found.");
        for (LLMRequest q : model.requests) assertThat(ReActAgentCompactionNativeTest.size(q)).isLessThanOrEqualTo(WINDOW);
    }

    @Test
    void conversationHistoryAndTheSystemPromptCountAgainstTheWindow() {
        ScriptedModel model = looping(20);
        io.github.llm4j.agent.memory.ConversationHistory history = new io.github.llm4j.agent.memory.ConversationHistory(50);
        for (int i = 0; i < 6; i++) {
            history.addUserMessage("earlier question " + i + " " + "y".repeat(300));
            history.addAssistantMessage("earlier answer " + i + " " + "z".repeat(300));
        }
        ContextListener listener = new ContextListener();
        agent(model, 1_200).conversationHistory(history).contextPolicy(ReActAgentCompactionNativeTest.policy()).compaction(ContextCompactors.dropOldest())
                .addListener(listener).build().run("Q?");
        for (LLMRequest q : model.requests) assertThat(ReActAgentCompactionNativeTest.size(q)).isLessThanOrEqualTo(WINDOW);
        assertThat(listener.compacted()).isGreaterThan(0);
    }
}
