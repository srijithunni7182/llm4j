package io.github.llm4j.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verification plan L1–L9 against the real APIs, for every provider/model whose credentials are set
 * (see {@link LiveTargets}). Run with {@code mvn -pl ai-agent4j -Plive test}; never part of a default build.
 */
@Tag("live")
class LiveProviderContractTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static Stream<LiveTargets.Target> targets() {
        List<LiveTargets.Target> all = LiveTargets.all();
        // JUnit needs at least one argument: an empty list becomes one placeholder that is skipped
        return all.isEmpty() ? Stream.of(new LiveTargets.Target("none", "none", k -> null)) : all.stream();
    }

    static void present(LiveTargets.Target t) {
        assumeTrue(!t.provider().equals("none"), "no provider credentials set (see LiveTargets)");
    }

    static LLMResponse ask(LLMClient client, LLMRequest request) {
        return client.chat(request);
    }

    @ParameterizedTest(name = "L1 {0}")
    @MethodSource("targets")
    void l1_aPlainQuestionGetsAnAnswer(LiveTargets.Target t) {
        present(t);
        LLMResponse r = ask(t.client(), LLMRequest.builder().addUserMessage("In one short sentence: what is the capital of France?")
                .maxTokens(200).build());
        assertThat(r.getContent()).containsIgnoringCase("Paris");
        assertThat(r.getTokenUsage()).isNotNull();
        assertThat(r.getTokenUsage().getPromptTokens()).isPositive();
        assertThat(r.getTokenUsage().getCompletionTokens()).isPositive();
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.getModel()).isNotBlank();
    }

    @ParameterizedTest(name = "L2 {0}")
    @MethodSource("targets")
    void l2_theSystemPromptIsObeyed(LiveTargets.Target t) {
        present(t);
        LLMResponse r = ask(t.client(), LLMRequest.builder()
                .addSystemMessage("End every reply with the single word PINEAPPLE, in capitals.")
                .addUserMessage("Name one primary colour.").maxTokens(200).build());
        assertThat(r.getContent().strip()).containsIgnoringCase("PINEAPPLE");
    }

    @ParameterizedTest(name = "L3 {0}")
    @MethodSource("targets")
    void l3_turnsAreRemembered(LiveTargets.Target t) {
        present(t);
        LLMResponse r = ask(t.client(), LLMRequest.builder()
                .addUserMessage("My name is Asha.")
                .addAssistantMessage("Nice to meet you, Asha!")
                .addUserMessage("What is my name? Reply with just the name.").maxTokens(100).build());
        assertThat(r.getContent()).contains("Asha");
    }

    @ParameterizedTest(name = "L4 {0}")
    @MethodSource("targets")
    void l4_hittingMaxTokensReadsAsLength(LiveTargets.Target t) {
        present(t);
        LLMResponse r = ask(t.client(), LLMRequest.builder()
                .addUserMessage("Write a 500-word story about a lighthouse keeper.").maxTokens(16).build());
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @ParameterizedTest(name = "L5 {0}")
    @MethodSource("targets")
    void l5_streamingYieldsChunksThenOneFinalChunk(LiveTargets.Target t) {
        present(t);
        List<LLMResponse> chunks;
        try (Stream<LLMResponse> s = t.client().chatStream(LLMRequest.builder()
                .addUserMessage("Count from 1 to 5, as digits separated by commas.").maxTokens(400).build())) {
            chunks = s.toList();
        }
        LLMResponse last = chunks.get(chunks.size() - 1);
        String text = String.join("", chunks.subList(0, chunks.size() - 1).stream().map(LLMResponse::getContent).toList());
        assertThat(chunks.size()).as("text chunks + final chunk").isGreaterThanOrEqualTo(2);
        assertThat(text).contains("1").contains("2").contains("3").contains("4").contains("5");
        assertThat(last.getContent()).isEmpty();
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(last.getTokenUsage()).isNotNull();
        assertThat(last.getTokenUsage().getCompletionTokens()).isPositive();
    }

    @ParameterizedTest(name = "L6 {0}")
    @MethodSource("targets")
    void l6_aReActAgentUsesATool(LiveTargets.Target t) {
        present(t);
        AgentResult result = ReActAgent.builder().llmClient(t.client()).addTool(new CalculatorTool()).maxIterations(6).build()
                .run("What is 1234 * 5678? Use the calculator tool, then give the number.");
        assertThat(result.getFinalAnswer().replace(",", "")).contains("7006652");
        assertThat(result.getSteps()).isNotEmpty();
        assertThat(result.isProtocolFollowed()).isTrue();
    }

    @ParameterizedTest(name = "L7 {0}")
    @MethodSource("targets")
    void l7_structuredOutputParses(LiveTargets.Target t) throws Exception {
        present(t);
        AgentResult result = ReActAgent.builder().llmClient(t.client()).maxIterations(3).build()
                .run("What is the capital of Japan? You MUST respond in valid JSON format only, following this schema: "
                        + "{\"city\": string, \"country\": string}");
        String answer = result.getFinalAnswer().strip().replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
        JsonNode node = JSON.readTree(answer);
        assertThat(node.path("city").asText()).containsIgnoringCase("Tokyo");
        assertThat(node.path("country").asText()).containsIgnoringCase("Japan");
    }

    @ParameterizedTest(name = "L8 {0}")
    @MethodSource("targets")
    void l8_budgetsChargeTheReportedUsage(LiveTargets.Target t) {
        present(t);
        Budget own = Budget.builder().name("l8").tokens(10_000).build();
        LLMClient metered = BudgetedLLMClient.builder(new DefaultLLMClient(t.provider(null))).budget(own).build();
        LLMResponse r = metered.chat(LLMRequest.builder().addUserMessage("Say hello.").maxTokens(50).build());
        long afterChat = own.spent().tokens();
        assertThat(afterChat).isEqualTo(r.getTokenUsage().getTotalTokens());
        LLMResponse last = null;
        try (Stream<LLMResponse> s = metered.chatStream(LLMRequest.builder().addUserMessage("Say goodbye.").maxTokens(50).build())) {
            for (LLMResponse c : (Iterable<LLMResponse>) s::iterator) last = c;
        }
        assertThat(own.spent().tokens() - afterChat).isEqualTo(last.getTokenUsage().getTotalTokens());
        LiveTargets.budget(t.provider()); // (shared budgets meter the other tests)
    }

    /** L9 needs no real key: a wrong one is rejected before anything is charged. */
    @Test
    void l9_aBadKeyIsAnAuthenticationFailure() {
        List<Throwable> failures = new ArrayList<>();
        for (var provider : List.of(
                new AnthropicProvider(LiveTargets.config("sk-ant-invalid-key-for-tests", null, "claude-haiku-4-5")),
                new GoogleProvider(LiveTargets.config("invalid-key-for-tests", null, "gemini-2.5-flash")))) {
            assertThatThrownBy(() -> provider.chat(LLMRequest.builder().addUserMessage("hi").maxTokens(5).build()))
                    .isInstanceOf(AuthenticationException.class)
                    .satisfies(failures::add)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("invalid-key-for-tests"));
        }
        failures.forEach(f -> System.out.println("L9 " + f.getClass().getSimpleName() + ": " + f.getMessage()));
    }
}
