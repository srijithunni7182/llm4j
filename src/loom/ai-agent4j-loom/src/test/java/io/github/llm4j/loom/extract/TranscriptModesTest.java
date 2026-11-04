package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.TranscriptMode;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.memory.MemoryEngine;
import io.github.llm4j.loom.memory.TranscriptAccumulationEngine;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.DefaultVariableContext;
import io.github.llm4j.loom.runtime.VariableContext;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 24, R8: an ordinary agent may leave the transcript ({@code memory { transcript: none | own
 * | all }}) or the shared prompt ({@code shared_prompt: none}). The default is unchanged.
 */
class TranscriptModesTest {

    static final String SHARED = "SHARED-CANARY-77";

    static final String SCRIPT =
            """
            shared_prompt: "SHARED-CANARY-77"
            agent Alpha { model: "test" system: "You are Alpha." }
            agent Beta { model: "test" system: "You are Beta." memory { transcript: own } }
            agent Gamma { model: "test" system: "You are Gamma." memory { transcript: none } shared_prompt: none }
            agent Delta { model: "test" system: "You are Delta." }
            workflow Main() {
                delegate "alpha-one" to Alpha -> a1
                delegate "beta-one" to Beta -> b1
                delegate "gamma-one" to Gamma -> g1
                delegate "beta-two" to Beta -> b2
                delegate "delta-one" to Delta -> d1
            }
            workflow Passing() {
                delegate "alpha-one" to Alpha -> a1
                handoff "to-gamma" to Gamma
            }
            """;

    /** The text of each request, by the agent that made it. */
    static Map<String, List<String>> run(String script, String workflow) {
        Map<String, List<String>> byAgent = new java.util.LinkedHashMap<>();
        io.github.llm4j.loom.extract.CapturingClient client =
                new CapturingClient("reply") {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        String text = CapturingClient.text(request);
                        String who = text.replaceAll("(?s).*You are (\\w+)\\..*", "$1");
                        byAgent.computeIfAbsent(who, k -> new ArrayList<>()).add(text);
                        return LLMResponse.builder()
                                .content(who + "-said-this")
                                .model("test")
                                .build();
                    }
                };
        LoomScript parsed = new LoomParser(new Lexer(script).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(parsed, new ToolRegistry(), m -> client);
        executor.initialize();
        executor.executeWorkflow(workflow, Map.of());
        return byAgent;
    }

    // ---- the default is unchanged

    @Test
    void allIsTheDefaultAndSeesEverythingSaidBefore() {
        Map<String, List<String>> seen = run(SCRIPT, "Main");

        String delta = seen.get("Delta").get(0);
        assertThat(delta)
                .contains("Conversation History")
                .contains("Alpha-said-this")
                .contains("Beta-said-this")
                .contains("Gamma-said-this");
        assertThat(delta).contains(SHARED);
    }

    @Test
    void anAgentWithoutTheSettingHasModeAll() {
        LoomScript parsed = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();

        assertThat(parsed.getAgents().get(0).getTranscriptMode()).isEqualTo(TranscriptMode.ALL);
        assertThat(parsed.getAgents().get(1).getTranscriptMode()).isEqualTo(TranscriptMode.OWN);
        assertThat(parsed.getAgents().get(2).getTranscriptMode()).isEqualTo(TranscriptMode.NONE);
        assertThat(parsed.getAgents().get(0).isSharedPromptExcluded()).isFalse();
        assertThat(parsed.getAgents().get(2).isSharedPromptExcluded()).isTrue();
    }

    // ---- none and own

    @Test
    void noneReceivesNothingBeyondItsTask() {
        String gamma = run(SCRIPT, "Main").get("Gamma").get(0);

        assertThat(gamma)
                .contains("gamma-one")
                .doesNotContain("Conversation History")
                .doesNotContain("Alpha-said-this")
                .doesNotContain("Beta-said-this");
    }

    @Test
    void ownReceivesOnlyItsOwnEarlierExchanges() {
        List<String> beta = run(SCRIPT, "Main").get("Beta");

        assertThat(beta.get(0)).doesNotContain("Alpha-said-this").doesNotContain("Conversation History");
        assertThat(beta.get(1))
                .contains("Conversation History")
                .contains("beta-one")
                .contains("Beta-said-this")
                .doesNotContain("Alpha-said-this")
                .doesNotContain("Gamma-said-this");
    }

    @Test
    void anAgentThatOptsOutIsStillSeenByOthers() {
        String delta = run(SCRIPT, "Main").get("Delta").get(0);

        assertThat(delta).contains("Gamma-said-this").contains("Beta-said-this");
    }

    @Test
    void handoffHonoursTheMode() {
        String gamma = run(SCRIPT, "Passing").get("Gamma").get(0);

        assertThat(gamma).doesNotContain("Alpha-said-this").doesNotContain("Conversation History");
    }

    // ---- the shared prompt

    @Test
    void sharedPromptNoneLeavesTheAgentOutOfIt() {
        Map<String, List<String>> seen = run(SCRIPT, "Main");

        assertThat(seen.get("Gamma").get(0)).doesNotContain(SHARED);
        assertThat(seen.get("Alpha").get(0)).contains(SHARED);
    }

    @Test
    void sharedPromptTakesOnlyTheWordNone() {
        assertThatThrownBy(
                        () ->
                                new LoomParser(
                                                new Lexer(
                                                                "agent A { model: \"m\" shared_prompt: \"own text\" }")
                                                        .tokenize())
                                        .parseScript())
                .hasMessageContaining("shared_prompt")
                .hasMessageContaining("none");
    }

    // ---- checks

    private static List<ScriptValidator.Problem> problems(String source) {
        LoomScript parsed = new LoomParser(new Lexer(source).tokenize()).parseScript();
        HarnessExecutor executor =
                new HarnessExecutor(parsed, new ToolRegistry(), m -> new CapturingClient("ok"));
        return new ScriptValidator().validate(parsed, executor.validationContext());
    }

    @Test
    void anUnknownTranscriptModeIsRefusedWithTheChoices() {
        var problems = problems("agent A { model: \"m\" memory { transcript: some } }\nworkflow W() { delegate \"x\" to A -> r }");

        assertThat(problems.toString()).contains("transcript").contains("none").contains("own").contains("all");
    }

    @Test
    void noneWithMaxTokensWarnsThatItHasNoEffect() {
        var problems =
                problems(
                        "agent A { model: \"m\" memory { transcript: none max_tokens: 500 } }\nworkflow W() { delegate \"x\" to A -> r }");

        assertThat(problems)
                .anyMatch(
                        p ->
                                p.severity() == ScriptValidator.Severity.WARNING
                                        && p.message().contains("no effect"));
    }

    @Test
    void aMemoryBlockThatOnlySetsTheTranscriptIsValid() {
        var problems = problems("agent A { model: \"m\" memory { transcript: none } }\nworkflow W() { delegate \"x\" to A -> r }");

        assertThat(problems).noneMatch(p -> p.severity() == ScriptValidator.Severity.ERROR);
    }

    // ---- hosts and rewinds

    @Test
    void aCustomMemoryEngineIsToldTheModeThroughTheAgentDefinition() {
        List<TranscriptMode> told = new ArrayList<>();
        MemoryEngine custom =
                new MemoryEngine() {
                    @Override
                    public String assembleContext(AgentDef a, String task, VariableContext c) {
                        told.add(a.getTranscriptMode());
                        return task;
                    }

                    @Override
                    public void storeOutcome(AgentDef a, String t, AgentResult r, VariableContext c) {}
                };
        LoomScript parsed = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor =
                new HarnessExecutor(
                        parsed, new ToolRegistry(), m -> new CapturingClient("ok"));
        executor.setMemoryEngine(custom);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        assertThat(told)
                .containsExactly(
                        TranscriptMode.ALL,
                        TranscriptMode.OWN,
                        TranscriptMode.NONE,
                        TranscriptMode.OWN,
                        TranscriptMode.ALL);
    }

    @Test
    void aRewindRestoresWhatEachAgentReceivesPerMode() {
        TranscriptAccumulationEngine engine = new TranscriptAccumulationEngine();
        AgentDef own = agent("Own", TranscriptMode.OWN);
        AgentDef all = agent("All", TranscriptMode.ALL);
        VariableContext ctx = new DefaultVariableContext();
        engine.storeOutcome(own, "own-task-1", AgentResult.builder().finalAnswer("own-answer-1").build(), ctx);
        Object mark = engine.mark();
        engine.storeOutcome(own, "own-task-2", AgentResult.builder().finalAnswer("own-answer-2").build(), ctx);
        engine.storeOutcome(all, "all-task-2", AgentResult.builder().finalAnswer("all-answer-2").build(), ctx);

        engine.restore(mark);

        assertThat(engine.assembleContext(own, "now", ctx))
                .contains("own-answer-1")
                .doesNotContain("own-answer-2");
        assertThat(engine.assembleContext(all, "now", ctx))
                .contains("own-answer-1")
                .doesNotContain("all-answer-2");
    }

    private static AgentDef agent(String name, TranscriptMode mode) {
        AgentDef a = new AgentDef(name);
        a.setTranscriptMode(mode);
        return a;
    }
}
