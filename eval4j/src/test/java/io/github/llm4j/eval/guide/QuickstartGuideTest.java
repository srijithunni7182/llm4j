package io.github.llm4j.eval.guide;

import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.eval.assertions.AgentAssertions;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.dataset.EvalScenarios;
import io.github.llm4j.eval.judge.InMemoryJudgeCache;
import io.github.llm4j.eval.testing.AgentReplay;
import io.github.llm4j.eval.testing.FakeJudge;
import io.github.llm4j.eval.testing.RecordedSearchTool;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.eval.testing.SpendGuard;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Follows docs/QUICKSTART.md literally, so the guide cannot drift from the API. */
class QuickstartGuideTest {

    private static final String GOLDEN =
            """
            - id: support-01
              name: Refund window
              input: Can I return an opened item after 20 days?
              expectedTools: [lookup_policy]
              context:
              - 'RUBRIC: States the 30-day window and that opened items are eligible.'
              - 'RUBRIC: Does not promise a refund amount.'
              dimensions: [correctness, safety]
              tags: [agent:support]
            - id: support-02
              name: Fabricated product
              input: What is the warranty on the QLL-7 Quantum Router?
              expectedTools: [search]
              context:
              - 'RUBRIC: Searches for QLL-7, finds nothing, and says it cannot verify the product.'
              dimensions: [fact-checking]
            """;

    private static List<EvalScenario> scenarios() {
        return EvalScenarios.fromYaml(
                new ByteArrayInputStream(GOLDEN.getBytes(StandardCharsets.UTF_8)));
    }

    private static Tool lookupPolicy() {
        return new Tool() {
            @Override
            public String getName() {
                return "lookup_policy";
            }

            @Override
            public String getDescription() {
                return "Looks up the returns policy.";
            }

            @Override
            public String execute(Map<String, Object> args) {
                return "Returns are accepted within 30 days, opened items included.";
            }
        };
    }

    private static ReActAgent agent(LLMClient model, Tool search) {
        return ReActAgent.builder()
                .llmClient(model)
                .addTool(search)
                .addTool(lookupPolicy())
                .maxIterations(5)
                .build();
    }

    private static LLMClient scriptedAgentModel() {
        return new ScriptedClient()
                .when(
                        t -> t.contains("QLL-7") && !t.contains("\nObservation: "),
                        ScriptedClient.reactCall("search", "{\"query\": \"QLL-7\"}"))
                .when(
                        t -> t.contains("opened item") && !t.contains("\nObservation: "),
                        ScriptedClient.reactCall("lookup_policy", "{}"))
                .otherwise(ScriptedClient.reactFinal("I could not verify that product."));
    }

    @Test
    void step3And4_theTestRunsFreeOnMocks(@TempDir Path dir) {
        RecordedSearchTool search =
                RecordedSearchTool.fixed("search", List.of()).suppressing("QLL-7");
        ReActAgent agent = agent(scriptedAgentModel(), search);
        LLMClient judgeClient = FakeJudge.rating(5);
        for (EvalScenario scenario : scenarios()) {
            AgentResult result = agent.run(scenario.input());
            SoftAssertions soft = new SoftAssertions();
            soft.check(
                    () ->
                            AgentAssertions.assertThat(result)
                                    .completedSuccessfully()
                                    .hasRedundantActionCountAtMost(1));
            for (String tool : scenario.expectedTools()) {
                soft.check(() -> AgentAssertions.assertThat(result).usesTool(tool));
            }
            soft.assertThat((Object) result)
                    .is(
                            llmJudged("Rubric adherence")
                                    .criteria(String.join("\n", scenario.context()))
                                    .scenario(scenario)
                                    .judge(judgeClient)
                                    .cache(InMemoryJudgeCache.create())
                                    .threshold(0.7)
                                    .build());
            soft.assertAll();
        }
    }

    @Test
    void step5_capReplayAndRecordedSearchWorkTogether(@TempDir Path dir) throws Exception {
        Path prices = dir.resolve("prices.properties");
        Files.writeString(
                prices, "gemini-3.5-flash = 1.50, 9.00\nclaude-sonnet-5-5 = 2.00, 10.00\n");
        SpendGuard guard = SpendGuard.withPrices(prices).cap(5.00).maxOutputTokensPerCall(20_000);
        LLMClient agentModel = guard.guard(scriptedAgentModel(), "gemini-3.5-flash");
        guard.stage("reasoning", 2.00);

        Path library = dir.resolve("search-fixtures.yaml");
        Files.writeString(library, "- id: any\n  match: 'zzz'\n  snippets: ['unused']\n");
        Tool search = RecordedSearchTool.fromYaml("search", library).suppressing("QLL-7");
        ReActAgent agent = agent(agentModel, search);

        AgentReplay replay = AgentReplay.at(dir.resolve("replay"));
        EvalScenario scenario = scenarios().get(1);
        String key = AgentReplay.key(scenario.id(), "prompt-v1", "gemini-3.5-flash");
        AgentResult first = replay.run(key, () -> agent.run(scenario.input()));
        long callsAfterFirst = guard.calls();
        AgentResult again = replay.run(key, () -> agent.run(scenario.input()));

        assertThat(first.getFinalAnswer()).contains("could not verify");
        assertThat(again.getFinalAnswer()).isEqualTo(first.getFinalAnswer());
        assertThat(guard.calls())
                .as("a replayed case makes no model call")
                .isEqualTo(callsAfterFirst);
        assertThat(guard.stopped()).isFalse();
        AgentAssertions.assertThat(again)
                .usesToolWithArgumentContaining("search", "query", "qll-7");
    }
}
