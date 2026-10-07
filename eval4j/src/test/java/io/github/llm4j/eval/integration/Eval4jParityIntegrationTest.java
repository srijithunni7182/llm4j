package io.github.llm4j.eval.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.compare.PromptComparison;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.dataset.synthesis.DatasetSynthesizer;
import io.github.llm4j.eval.dataset.synthesis.SynthesisOptions;
import io.github.llm4j.eval.judge.ConversationJudgePresets;
import io.github.llm4j.eval.judge.FileSystemJudgeCache;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.eval.judge.LlmJudgePresets;
import io.github.llm4j.eval.judge.Transcript;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.ollama.OllamaProvider;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live checks of the deepeval-parity features against a real judge (Gemini, or a local Ollama
 * server when {@code EVAL4J_JUDGE=ollama} / {@code OLLAMA_MODEL} / {@code OLLAMA_BASE_URL} is set —
 * small local models are noisier, so prefer a 7B+ instruct model): does real model output parse,
 * and do scores move in the right direction? Not run by {@code mvn test}; run with {@code mvn -pl
 * eval4j -am verify -P integration-tests} and {@code GEMINI_API_KEY} (or {@code GOOGLE_API_KEY})
 * set. Skipped, not failed, without a key. Assertions check direction with lenient margins, never
 * exact scores. Judge calls are cached under {@code target/eval4j-live-cache} to limit spend.
 */
@Tag("integration")
class Eval4jParityIntegrationTest {

    private static LLMClient judge;

    @BeforeAll
    static void setUp() {
        String ollamaModel = System.getenv("OLLAMA_MODEL");
        String ollamaUrl = System.getenv("OLLAMA_BASE_URL");
        boolean useOllama =
                "ollama".equalsIgnoreCase(System.getenv("EVAL4J_JUDGE"))
                        || (ollamaModel != null && !ollamaModel.isBlank())
                        || (ollamaUrl != null && !ollamaUrl.isBlank());
        String anthropicKey = System.getenv("EVAL4J_ANTHROPIC_API_KEY");
        boolean useAnthropic =
                "anthropic".equalsIgnoreCase(System.getenv("EVAL4J_JUDGE"))
                        || (anthropicKey != null && !anthropicKey.isBlank());
        if (useAnthropic) {
            judge = anthropicJudge(anthropicKey);
        } else if (useOllama) {
            judge = ollamaJudge(ollamaUrl, ollamaModel);
        } else {
            judge = geminiJudge();
        }
    }

    /**
     * Claude judge: key from {@code EVAL4J_ANTHROPIC_API_KEY}, model from {@code
     * EVAL4J_ANTHROPIC_MODEL} (default Haiku 4.5).
     */
    private static LLMClient anthropicJudge(String apiKey) {
        assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "EVAL4J_ANTHROPIC_API_KEY not set - skipping live parity checks");
        String model = System.getenv("EVAL4J_ANTHROPIC_MODEL");
        if (model == null || model.isBlank()) {
            model = "claude-haiku-4-5-20251001";
        }
        return new DefaultLLMClient(
                new AnthropicProvider(
                        LLMConfig.builder().apiKey(apiKey).defaultModel(model).build()));
    }

    /**
     * Local (or remote) Ollama server; {@code OLLAMA_BASE_URL} like {@code http://host:11434/api}.
     */
    private static LLMClient ollamaJudge(String baseUrl, String model) {
        LLMConfig.Builder discovery = LLMConfig.builder();
        if (baseUrl != null && !baseUrl.isBlank()) {
            discovery.baseUrl(baseUrl);
        }
        String resolved = model;
        try {
            if (resolved == null || resolved.isBlank()) {
                resolved = new OllamaProvider(discovery.build()).getFirstAvailableModel();
            }
        } catch (RuntimeException e) {
            resolved = null;
        }
        assumeTrue(
                resolved != null && !resolved.isBlank(),
                "No Ollama model available (start `ollama serve`, pull a model, or set"
                        + " OLLAMA_MODEL/OLLAMA_BASE_URL) - skipping live parity checks");
        LLMConfig.Builder config = LLMConfig.builder().defaultModel(resolved);
        if (baseUrl != null && !baseUrl.isBlank()) {
            config.baseUrl(baseUrl);
        }
        return new DefaultLLMClient(new OllamaProvider(config.build()));
    }

    private static LLMClient geminiJudge() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GOOGLE_API_KEY");
        }
        assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "No judge configured: set GEMINI_API_KEY/GOOGLE_API_KEY, or EVAL4J_JUDGE=ollama"
                        + " (optionally OLLAMA_MODEL, OLLAMA_BASE_URL) - skipping live parity checks");
        String model =
                new GoogleProvider(LLMConfig.builder().apiKey(apiKey).build())
                        .getFirstAvailableModel();
        return new DefaultLLMClient(
                new GoogleProvider(LLMConfig.builder().apiKey(apiKey).defaultModel(model).build()));
    }

    private static final String QUESTION =
            "What is the capital of France and what river runs through it?";
    private static final String ANSWER =
            "Paris is the capital of France, and the Seine runs through it.";

    @Test
    void contextualRelevancyAndPrecision_dropWhenIrrelevantChunksAreRankedFirst() {
        var presets = LlmJudgePresets.using(judge);
        List<String> good =
                List.of(
                        "Paris is the capital and largest city of France.",
                        "The Seine is a river that flows through Paris.",
                        "The Eiffel Tower is a landmark in Paris.");
        List<String> noisy =
                List.of(
                        "Bananas are rich in potassium.",
                        "The 1998 World Cup was hosted in France.",
                        "Paris is the capital and largest city of France.",
                        "The Seine is a river that flows through Paris.");
        double goodRelevancy = presets.contextualRelevancy(QUESTION, good).evaluate().score();
        double noisyRelevancy = presets.contextualRelevancy(QUESTION, noisy).evaluate().score();
        double noisyPrecision =
                presets.contextualPrecision(QUESTION, ANSWER, noisy).evaluate().score();
        assertThat(goodRelevancy).isGreaterThan(noisyRelevancy);
        assertThat(noisyPrecision).isLessThan(1.0);
    }

    @Test
    void contextualRecall_isLowWhenTheAnswerIsMissingFromContext() {
        var presets = LlmJudgePresets.using(judge);
        double covered =
                presets.contextualRecall(
                                QUESTION,
                                ANSWER,
                                List.of(
                                        "Paris is the capital of France.",
                                        "The Seine flows through Paris."))
                        .evaluate()
                        .score();
        double missing =
                presets.contextualRecall(
                                QUESTION, ANSWER, List.of("Bananas are rich in potassium."))
                        .evaluate()
                        .score();
        assertThat(covered).isGreaterThan(missing);
    }

    @Test
    void knowledgeRetention_flagsAnAssistantThatReAsksForKnownFacts() {
        var conv = ConversationJudgePresets.using(judge);
        Transcript forgetful =
                Transcript.builder()
                        .user("Hi, my name is Sam and I live in Lisbon.")
                        .assistant("Nice to meet you, Sam!")
                        .user("Can you recommend a cafe near me?")
                        .assistant("Sure - first, what is your name and which city do you live in?")
                        .build();
        Transcript attentive =
                Transcript.builder()
                        .user("Hi, my name is Sam and I live in Lisbon.")
                        .assistant("Nice to meet you, Sam!")
                        .user("Can you recommend a cafe near me?")
                        .assistant("Sam, since you are in Lisbon, try a pastelaria in Alfama.")
                        .build();
        JudgeVerdict bad = conv.knowledgeRetention().evaluate(forgetful);
        JudgeVerdict good = conv.knowledgeRetention().evaluate(attentive);
        assertThat(good.score()).isGreaterThan(bad.score());
    }

    @Test
    void pairwise_prefersTheClearlyBetterAnswer_regardlessOfPosition() {
        List<EvalScenario> scenarios =
                List.of(
                        new EvalScenario(
                                "capital",
                                "What is the capital of France?",
                                null,
                                null,
                                null,
                                null,
                                null),
                        new EvalScenario(
                                "water",
                                "At what temperature does water boil at sea level?",
                                null,
                                null,
                                null,
                                null,
                                null));
        PromptComparison.Result result =
                PromptComparison.using(judge)
                        .cache(FileSystemJudgeCache.at(Path.of("target/eval4j-live-cache")))
                        .criteria("Correct, direct and helpful")
                        .variantA("degraded", s -> "I am not sure, maybe ask someone else.")
                        .variantB(
                                "good",
                                s ->
                                        s.name().equals("capital")
                                                ? "The capital of France is Paris."
                                                : "Water boils at 100 degrees Celsius at sea level.")
                        .scenarios(scenarios)
                        .run();
        assertThat(result.winRateB()).isGreaterThanOrEqualTo(0.5);
        assertThat(result.winsA()).isZero();
    }

    @Test
    void synthesis_producesUsableScenariosFromADocument() {
        String doc =
                "The Great Barrier Reef, off the coast of Queensland, Australia, is the world's largest"
                        + " coral reef system, stretching over 2,300 kilometres and composed of more than"
                        + " 2,900 individual reefs.";
        var result =
                DatasetSynthesizer.using(judge)
                        .fromDocuments(
                                List.of(doc), SynthesisOptions.defaults().scenariosPerDocument(2));
        assertThat(result.scenarios()).isNotEmpty();
        assertThat(result.scenarios())
                .allSatisfy(
                        s -> {
                            assertThat(s.input()).isNotBlank();
                            assertThat(s.expectedOutput()).isNotBlank();
                            assertThat(s.context()).containsExactly(doc);
                        });
    }
}
