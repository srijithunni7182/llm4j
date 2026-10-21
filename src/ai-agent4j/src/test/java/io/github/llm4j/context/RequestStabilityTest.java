package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.AgentEventListener;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.model.LLMRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins what the agent sends to the model when nothing about context is configured (R4, R17, R21).
 * The golden files were recorded from the code <b>before</b> context management was added; they are
 * changed only on purpose, with {@code -Dgolden.write=true}.
 */
class RequestStabilityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/context/golden");

    private static void matches(String name, List<LLMRequest> requests) throws Exception {
        String actual = RequestRenderer.render(requests);
        Path file = GOLDEN.resolve(name + ".txt");
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(GOLDEN);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(actual)
                .as("requests sent in the '" + name + "' scenario")
                .isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static ReActAgent.Builder agent(ScriptedModel model, ScriptedModel.FixedTool... tools) {
        ReActAgent.Builder b = ReActAgent.builder().llmClient(model).maxIterations(10);
        for (ScriptedModel.FixedTool t : tools) b.addTool(t);
        return b;
    }

    @Test
    void nativeLoopWithTwoToolSteps() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        true,
                        false,
                        ScriptedModel.calls(
                                "looking",
                                ScriptedModel.call("c1", "search", Map.of("q", "plans"))),
                        ScriptedModel.calls(
                                "and again",
                                ScriptedModel.call("c2", "search", Map.of("q", "prices")),
                                ScriptedModel.call("c3", "lookup", Map.of("id", 7))),
                        ScriptedModel.answer("The plans differ in price."));
        AgentResult r =
                agent(
                                model,
                                new ScriptedModel.FixedTool("search", "result text for search"),
                                new ScriptedModel.FixedTool("lookup", "record 7"))
                        .temperature(0.2)
                        .build()
                        .run("Compare the plans.");
        assertThat(r.getFinalAnswer()).contains("plans differ");
        matches("native-two-steps", model.requests);
    }

    @Test
    void nativeLoopWithNoTools() throws Exception {
        ScriptedModel model = ScriptedModel.of(true, false, ScriptedModel.answer("hello"));
        agent(model).instructions("Be brief.").build().run("Say hello.");
        matches("native-no-tools", model.requests);
    }

    @Test
    void textLoopWithTwoToolSteps() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        false,
                        false,
                        ScriptedModel.textStep("I should search.", "search", "{\"q\":\"plans\"}"),
                        ScriptedModel.textStep("Now prices.", "search", "{\"q\":\"prices\"}"),
                        ScriptedModel.textAnswer("The plans differ in price."));
        AgentResult r =
                agent(model, new ScriptedModel.FixedTool("search", "result text for search"))
                        .build()
                        .run("Compare the plans.");
        assertThat(r.getFinalAnswer()).contains("plans differ");
        matches("text-two-steps", model.requests);
    }

    @Test
    void textLoopWithAnUnknownToolAndEmptyAction() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        false,
                        false,
                        ScriptedModel.textStep("Try a tool that is not there.", "nope", "{}"),
                        ScriptedModel.answer("Thought: nothing to do\n"),
                        ScriptedModel.textAnswer("done"));
        agent(model, new ScriptedModel.FixedTool("search", "x")).build().run("Q?");
        matches("text-unknown-tool", model.requests);
    }

    /** R21: a listener written before context management existed still compiles and works. */
    @Test
    void anOldStyleListenerStillWorks() {
        List<String> seen = new java.util.ArrayList<>();
        AgentEventListener old =
                new AgentEventListener() {
                    @Override
                    public void onThought(String thought) {
                        seen.add("thought");
                    }

                    @Override
                    public void onAction(String toolName, String toolInput) {
                        seen.add("action");
                    }

                    @Override
                    public void onObservation(String observation) {
                        seen.add("observation");
                    }
                };
        ScriptedModel model =
                ScriptedModel.of(
                        true,
                        false,
                        ScriptedModel.calls("t", ScriptedModel.call("c1", "search", Map.of())),
                        ScriptedModel.answer("ok"));
        agent(model, new ScriptedModel.FixedTool("search", "r")).addListener(old).build().run("Q?");
        assertThat(seen).contains("action", "observation");
    }

    /**
     * R4: listeners, a window and a catalogue without a compactor change nothing about what is
     * sent.
     */
    @Test
    void aListenerAndAWindowWithoutACompactorSendTheSameRequests() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        true,
                        false,
                        ScriptedModel.calls(
                                "looking",
                                ScriptedModel.call("c1", "search", Map.of("q", "plans"))),
                        ScriptedModel.calls(
                                "and again",
                                ScriptedModel.call("c2", "search", Map.of("q", "prices")),
                                ScriptedModel.call("c3", "lookup", Map.of("id", 7))),
                        ScriptedModel.answer("The plans differ in price."));
        agent(
                        model,
                        new ScriptedModel.FixedTool("search", "result text for search"),
                        new ScriptedModel.FixedTool("lookup", "record 7"))
                .temperature(0.2)
                .contextWindow(500)
                .addListener(new ContextListener())
                .build()
                .run("Compare the plans.");
        matches("native-two-steps", model.requests);
    }

    /**
     * R4 for the text loop: a listener and a window without a compactor change nothing about what
     * is sent.
     */
    @Test
    void aListenerAndAWindowWithoutACompactorSendTheSameTextRequests() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        false,
                        false,
                        ScriptedModel.textStep("I should search.", "search", "{\"q\":\"plans\"}"),
                        ScriptedModel.textStep("Now prices.", "search", "{\"q\":\"prices\"}"),
                        ScriptedModel.textAnswer("The plans differ in price."));
        agent(model, new ScriptedModel.FixedTool("search", "result text for search"))
                .contextWindow(500)
                .addListener(new ContextListener())
                .build()
                .run("Compare the plans.");
        matches("text-two-steps", model.requests);
    }

    /**
     * R17: a text-loop run that mixes steps, an unknown tool and a reply with no action, with a
     * manager attached but nothing to compact.
     */
    @Test
    void theUnknownToolScenarioIsIdenticalWithAManagerAttached() throws Exception {
        ScriptedModel model =
                ScriptedModel.of(
                        false,
                        false,
                        ScriptedModel.textStep("Try a tool that is not there.", "nope", "{}"),
                        ScriptedModel.answer("Thought: nothing to do\n"),
                        ScriptedModel.textAnswer("done"));
        agent(model, new ScriptedModel.FixedTool("search", "x"))
                .contextWindow(1_000_000)
                .addListener(new ContextListener())
                .build()
                .run("Q?");
        matches("text-unknown-tool", model.requests);
    }
}
