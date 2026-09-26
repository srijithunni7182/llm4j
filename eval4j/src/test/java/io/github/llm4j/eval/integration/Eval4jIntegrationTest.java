package io.github.llm4j.eval.integration;

import static io.github.llm4j.eval.assertions.AgentAssertions.assertThat;
import static io.github.llm4j.eval.judge.LlmJudgeCondition.llmJudged;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.provider.google.GoogleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs eval4j's assertions and LLM-as-judge conditions against a real Gemini-backed {@code
 * ReActAgent} — the "does this actually work against a real model" check that mocked unit tests
 * can't give you. Not run by {@code mvn test} (excluded like every other {@code *IntegrationTest}
 * in this repo); run it with {@code mvn -pl eval4j -am verify -P integration-tests}, having set
 * {@code GEMINI_API_KEY} (or {@code GOOGLE_API_KEY}) in the environment. With neither set, every
 * test here is skipped rather than failed.
 *
 * <p>Agent behavior is inherently probabilistic, so assertions below check for reasonable
 * agent/judge behavior rather than exact wording — the same philosophy as {@code
 * ReActAgentIntegrationTest} in ai-agent4j.
 */
class Eval4jIntegrationTest {

    private static LLMClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "GEMINI_API_KEY (or GOOGLE_API_KEY) not set - skipping eval4j integration test");

        LLMConfig discoveryConfig = LLMConfig.builder().apiKey(apiKey).build();
        String model = new GoogleProvider(discoveryConfig).getFirstAvailableModel();

        LLMConfig config = LLMConfig.builder().apiKey(apiKey).defaultModel(model).build();
        client = new DefaultLLMClient(new GoogleProvider(config));
    }

    @Test
    void agentUsesCalculatorAndJudgeConfirmsCorrectness() {
        ReActAgent agent =
                ReActAgent.builder()
                        .llmClient(client)
                        .addTool(new CalculatorTool())
                        .maxIterations(10)
                        .temperature(0.2)
                        .build();

        AgentResult result = agent.run("What is 15% of 240?");

        assertThat(result)
                .completedSuccessfully()
                .usesTool("calculator")
                .hasFinalAnswerContaining("36")
                .is(LlmJudgePresets.using(client).correctness("36"));
    }

    /**
     * Modeled on the Kingini example app's persona (see
     * examples/kingini's VoiceController): a Malayalam-speaking cat character who works a "meow"
     * into every answer. Reused here as the system prompt directly, rather than depending on the
     * kingini module, to demonstrate eval4j judging a persona-driven agent from one of the
     * showcase apps, not just a bare tool-using one.
     */
    @Test
    void personaAgentStaysInCharacter() {
        ReActAgent kinginiLikeAgent =
                ReActAgent.builder()
                        .llmClient(client)
                        .systemPrompt(
                                "You are Kingini, a cute and friendly female cat living in a"
                                        + " traditional Kerala ancestral home. You speak only in"
                                        + " Malayalam. You love answering questions from children."
                                        + " As a cat, you must frequently use the sound 'Meow'"
                                        + " (written as 'മ്യാവൂ' in Malayalam) naturally in your"
                                        + " starting or ending of sentences. Keep answers short and"
                                        + " playful. Use Malayalam script for text responses.")
                        .maxIterations(3)
                        .temperature(0.5)
                        .build();

        AgentResult result = kinginiLikeAgent.run("Why is the sky blue?");

        assertThat(result)
                .completedSuccessfully()
                .is(
                        llmJudged("Persona Adherence")
                                .criteria(
                                        "The response is written in Malayalam script, works a"
                                                + " playful cat 'meow' sound (Malayalam: മ്യാവൂ)"
                                                + " into the answer, and stays short and"
                                                + " child-friendly.")
                                .judge(client)
                                .threshold(0.6)
                                .build());
    }
}
