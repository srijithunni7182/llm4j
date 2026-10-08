package io.github.llm4j.provider.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.AuthenticationException;
import io.github.llm4j.exception.ContentBlockedException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.ProviderException;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.exception.ServiceUnavailableException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.LLMResponse.FinishReason;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.Providers;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.Headers;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The provider contract (spec: uniform-providers), checked the same way for every provider: each
 * subclass only says how its provider talks (wire formats), never what counts as correct.
 * Verification plan C1–C12 and C14.
 */
public abstract class ProviderContract {

    /** The API key given to every provider under test; it must never appear in errors or logs. */
    protected static final String SECRET = "sk-test-SECRET-4f9a2c";

    protected MockWebServer server;
    protected final ObjectMapper json = new ObjectMapper();
    private PrintStream originalErr;
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();

    // ── what each provider supplies ─────────────────────────────────────────────────────────

    /** The provider under test, pointed at {@code server} (via {@link #config}). */
    protected abstract LLMProvider provider(String model);

    /** A model name this provider accepts (and, for Anthropic, one that accepts sampling settings). */
    protected abstract String model();

    /** Where the system prompt landed in a request body (joined), or null if not in a native field. */
    protected abstract String nativeSystem(JsonNode request);

    /** The non-system turns of a request body, as "role: text", roles normalised to user/assistant. */
    protected abstract List<String> turns(JsonNode request);

    /** The settings as sent: max tokens, temperature, top_p, stop sequences (null when absent). */
    protected abstract Settings settings(JsonNode request);

    protected record Settings(Integer maxTokens, Double temperature, Double topP, List<String> stops) {}

    /** A successful answer. */
    protected abstract String answer(String text, int inputTokens, int outputTokens);

    /** An answer cut off by the token limit. */
    protected abstract String truncated(String text);

    /** A safety refusal naming this category, or null when the provider has no such signal. */
    protected abstract String refusal(String category);

    /** An error body for this status. */
    protected abstract String error(int status, String message);

    /** A 429 response that says the limit resets at {@code reset}. */
    protected abstract MockResponse rateLimited(Instant reset);

    /** A complete streamed answer made of these pieces. */
    protected abstract MockResponse stream(List<String> pieces, int inputTokens, int outputTokens);

    /** A stream that sends these pieces, then an error. */
    protected abstract MockResponse streamThenError(List<String> pieces);

    /** The exception a mid-stream error must end the stream with. */
    protected abstract Class<? extends RuntimeException> midStreamError();

    // ── set-up ──────────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        originalErr = System.err;
        System.setErr(new PrintStream(capturedErr, true));
    }

    @AfterEach
    void stopServer() throws Exception {
        System.setErr(originalErr);
        server.shutdown();
        // C12: whatever happened, the key never reached a log line
        assertThat(capturedErr.toString()).doesNotContain(SECRET);
    }

    /** Config pointed at the mock: the secret key, quick retries, and a short inline wait for 429s. */
    protected LLMConfig config(String baseUrl, String model) {
        return LLMConfig.builder()
                .apiKey(SECRET)
                .baseUrl(baseUrl)
                .defaultModel(model)
                .retryPolicy(RetryPolicy.builder()
                        .maxRetries(2)
                        .initialBackoff(Duration.ofMillis(1))
                        .maxBackoff(Duration.ofMillis(5))
                        .inlineWaitThreshold(Duration.ofSeconds(1))
                        .retryableStatusCodes(RetryPolicy.defaultPolicy().getRetryableStatusCodes())
                        .build())
                .build();
    }

    protected String url(String path) {
        return server.url(path).toString().replaceAll("/+$", "");
    }

    protected MockResponse ok(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    protected JsonNode sent() throws Exception {
        RecordedRequest r = server.takeRequest();
        return json.readTree(r.getBody().readUtf8());
    }

    static LLMRequest ask(String question) {
        return LLMRequest.builder().addUserMessage(question).build();
    }

    private static void noSecret(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            assertThat(String.valueOf(c.getMessage())).doesNotContain(SECRET);
        }
    }

    // ── C1–C3: requests mean the same ───────────────────────────────────────────────────────

    @Test
    void c1_systemMessagesGoToTheNativeSystemField() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider(model()).chat(LLMRequest.builder()
                .addSystemMessage("You are terse.")
                .addSystemMessage("Answer in English.")
                .addUserMessage("Hi")
                .build());
        JsonNode sent = sent();
        String system = nativeSystem(sent);
        assertThat(system).isNotNull().contains("You are terse.").contains("Answer in English.");
        assertThat(system.indexOf("You are terse.")).isLessThan(system.indexOf("Answer in English."));
        assertThat(turns(sent)).containsExactly("user: Hi");
    }

    @Test
    void c2_turnsKeepTheirOrder() throws Exception {
        server.enqueue(ok(answer("Asha", 5, 1)));
        provider(model()).chat(LLMRequest.builder()
                .addUserMessage("My name is Asha")
                .addAssistantMessage("Hi Asha")
                .addUserMessage("What is my name?")
                .build());
        assertThat(turns(sent())).containsExactly("user: My name is Asha", "assistant: Hi Asha", "user: What is my name?");
    }

    @Test
    void c3_settingsArePassedUnderTheProvidersNames() throws Exception {
        server.enqueue(ok(answer("ok", 5, 1)));
        provider(model()).chat(LLMRequest.builder().addUserMessage("Hi")
                .maxTokens(123).temperature(0.2).topP(0.9).stopSequences(List.of("END")).build());
        Settings s = settings(sent());
        assertThat(s.maxTokens()).isEqualTo(123);
        assertThat(s.temperature()).isEqualTo(0.2);
        assertThat(s.topP()).isEqualTo(0.9);
        assertThat(s.stops()).containsExactly("END");
    }

    // ── C4–C6: responses mean the same ──────────────────────────────────────────────────────

    @Test
    void c4_anAnswerIsTextUsageModelAndStop() {
        server.enqueue(ok(answer("Paris is the capital.", 12, 6)));
        LLMResponse r = provider(model()).chat(ask("Capital of France?"));
        assertThat(r.getContent()).isEqualTo("Paris is the capital.");
        assertThat(r.getTokenUsage()).isNotNull();
        assertThat(r.getTokenUsage().getPromptTokens()).isEqualTo(12);
        assertThat(r.getTokenUsage().getCompletionTokens()).isEqualTo(6);
        assertThat(r.getModel()).isNotBlank();
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(r.getMetadata()).containsKey(Providers.FINISH_REASON_RAW);
    }

    @Test
    void c5_truncationReadsAsLength() {
        server.enqueue(ok(truncated("Once upon a")));
        LLMResponse r = provider(model()).chat(ask("Tell a long story"));
        assertThat(r.getFinishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void c6_aRefusalIsContentBlocked() {
        String body = refusal("cyber");
        assumeThat(body).as("provider has no refusal signal").isNotNull();
        server.enqueue(ok(body));
        assertThatThrownBy(() -> provider(model()).chat(ask("…")))
                .isInstanceOf(ContentBlockedException.class)
                .satisfies(ProviderContract::noSecret);
    }

    // ── C7–C9: failures look the same ───────────────────────────────────────────────────────

    @Test
    void c7_authAndBadRequestsAreTyped() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody(error(401, "invalid x-api-key")));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isExactlyInstanceOf(AuthenticationException.class)
                .hasMessageContaining("401")
                .satisfies(ProviderContract::noSecret);

        server.enqueue(new MockResponse().setResponseCode(400).setBody(error(400, "max_tokens is too large")));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isExactlyInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("max_tokens is too large")
                .satisfies(e -> assertThat(((InvalidRequestException) e).getStatusCode()).isEqualTo(400))
                .satisfies(ProviderContract::noSecret);
    }

    @Test
    void c8_unavailableIsRetriedThenTyped() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody(error(503, "busy")));
        server.enqueue(ok(answer("recovered", 3, 1)));
        assertThat(provider(model()).chat(ask("hi")).getContent()).isEqualTo("recovered");

        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(503).setBody(error(503, "busy")));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOf(ServiceUnavailableException.class)
                .isInstanceOf(ProviderException.class)
                .satisfies(ProviderContract::noSecret);
    }

    @Test
    void c9_rateLimitsCarryWhenTheyReset() {
        Instant reset = Instant.now().plus(Duration.ofHours(2));
        server.enqueue(rateLimited(reset));
        assertThatThrownBy(() -> provider(model()).chat(ask("hi")))
                .isInstanceOfSatisfying(RateLimitException.class, e -> {
                    assertThat(e.info()).isNotNull();
                    assertThat(e.info().resetAt()).isAfter(Instant.now().plus(Duration.ofMinutes(30)));
                });
    }

    // ── C10–C11: streaming works the same ───────────────────────────────────────────────────

    @Test
    void c10_streamsTextThenOneFinalChunk() {
        List<String> pieces = List.of("Hel", "lo, ", "world");
        server.enqueue(stream(pieces, 9, 4));
        List<LLMResponse> chunks;
        try (Stream<LLMResponse> s = provider(model()).chatStream(ask("Greet"))) {
            chunks = s.toList();
        }
        LLMResponse last = chunks.get(chunks.size() - 1);
        List<LLMResponse> text = chunks.subList(0, chunks.size() - 1);
        assertThat(text).extracting(LLMResponse::getContent).containsExactlyElementsOf(pieces);
        assertThat(last.getContent()).isEmpty();
        assertThat(last.getFinishReason()).isEqualTo(FinishReason.STOP);
        assertThat(last.getTokenUsage()).isNotNull();
        assertThat(last.getTokenUsage().getPromptTokens()).isEqualTo(9);
        assertThat(last.getTokenUsage().getCompletionTokens()).isEqualTo(4);
        assertThat(chunks).filteredOn(c -> c.getFinishReason() != FinishReason.UNKNOWN).hasSize(1);
    }

    @Test
    void c10_closingEarlyReleasesTheConnection() {
        server.enqueue(stream(List.of("a", "b", "c", "d"), 1, 1));
        try (Stream<LLMResponse> s = provider(model()).chatStream(ask("x"))) {
            assertThat(s.findFirst()).isPresent();
        }
        server.enqueue(ok(answer("next", 1, 1)));
        assertThat(provider(model()).chat(ask("again")).getContent()).isEqualTo("next");
    }

    @Test
    void c11_streamErrorsAreTypedBeforeAndDuring() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody(error(401, "bad key")));
        assertThatThrownBy(() -> provider(model()).chatStream(ask("hi")).toList())
                .isExactlyInstanceOf(AuthenticationException.class)
                .satisfies(ProviderContract::noSecret);

        server.enqueue(streamThenError(List.of("partial ")));
        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> provider(model()).chatStream(ask("hi")).forEach(c -> received.add(c.getContent())))
                .isInstanceOf(midStreamError())
                .satisfies(ProviderContract::noSecret);
        assertThat(received).contains("partial ");
    }

    // ── C14: budgets meter streams from the final chunk ─────────────────────────────────────

    @Test
    void c14_budgetsChargeTheStreamsReportedUsage() {
        server.enqueue(stream(List.of("one ", "two"), 20, 7));
        Budget budget = Budget.builder().name("contract").tokens(10_000).build();
        BudgetedLLMClient client = BudgetedLLMClient.builder(new DefaultLLMClient(provider(model()))).budget(budget).build();
        try (Stream<LLMResponse> s = client.chatStream(ask("count"))) {
            s.forEach(c -> { });
        }
        assertThat(budget.spent().tokens()).isEqualTo(27);
    }

    // ── helpers for subclasses ──────────────────────────────────────────────────────────────

    protected static MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    protected static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    protected static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) return null;
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    protected static Integer integer(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asInt();
    }

    protected static Double decimal(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asDouble();
    }

    protected static Headers headers(Map<String, String> values) {
        return Headers.of(values);
    }
}
