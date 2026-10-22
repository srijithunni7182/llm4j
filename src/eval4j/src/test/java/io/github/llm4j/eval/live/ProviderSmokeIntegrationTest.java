package io.github.llm4j.eval.live;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.eval.testing.SpendGuard;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.model.Message;
import io.github.llm4j.model.ToolCall;
import io.github.llm4j.model.ToolCallDelta;
import io.github.llm4j.model.ToolSchema;
import io.github.llm4j.model.ToolSpec;
import io.github.llm4j.provider.LLMProvider;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.provider.google.GoogleProvider;
import io.github.llm4j.provider.openai.Dialect;
import io.github.llm4j.provider.openai.OpenAiCompatibleProvider;
import io.github.llm4j.provider.openai.OpenAiResponsesProvider;
import io.github.llm4j.provider.openai.ResponsesDialect;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The live smoke run (spec 08, TEST_STRATEGY §5): for every vendor whose key is in the environment,
 * one plain chat, one streamed chat, one tool call and its answer, one streamed tool call, and one
 * streamed call that asks to see reasoning, through a recording proxy that keeps the vendor's real
 * bytes. It has two jobs: record the fixtures the contract tests replay, and notice when a vendor
 * changes an interface.
 *
 * <p>Opt-in: {@code mvn -pl src/eval4j -Plive test -Dtest=ProviderSmokeIntegrationTest}. A {@link
 * SpendGuard} caps each vendor (default US$0.25, {@code SMOKE_CAP_USD}); prompts are tiny and
 * outputs are short. Keys come from {@code OPENAI_API_KEY}, {@code XAI_API_KEY}, {@code
 * PERPLEXITY_API_KEY}, {@code MOONSHOT_API_KEY}, {@code DASHSCOPE_API_KEY}, {@code
 * ANTHROPIC_API_KEY} (or {@code SPIKE_ANTHROPIC_KEY}) and {@code GEMINI_API_KEY} (or {@code
 * SMOKE_GEMINI_API_KEY}); a vendor without a key is skipped. {@code SMOKE_ONLY=openai,xai} limits
 * the run. Raw responses go to {@code target/smoke/<vendor>/}, with a summary in {@code
 * target/smoke/SUMMARY.md}; with {@code SMOKE_RECORD=true} the scrubbed responses are also written
 * as fixtures under ai-agent4j's test resources. A key is never printed or written.
 */
@Tag("live")
class ProviderSmokeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolSpec WEATHER =
            new ToolSpec(
                    "get_weather",
                    "Current weather for a city",
                    ToolSchema.object().string("city", "the city", true).build());

    /** One vendor under test. */
    private record Vendor(
            String id,
            String directory,
            List<String> keyVariables,
            String origin,
            String pathPrefix,
            String plainModel,
            String reasoningModel,
            Function<LLMConfig, LLMProvider> provider,
            boolean priced,
            Map<String, Object> toolParameters) {}

    private static final List<Vendor> VENDORS =
            List.of(
                    new Vendor(
                            "openai",
                            "openai",
                            List.of("OPENAI_API_KEY"),
                            "https://api.openai.com",
                            "/v1",
                            "gpt-6-luna",
                            "gpt-6-luna",
                            c -> new OpenAiCompatibleProvider(Dialect.OPENAI, c),
                            true,
                            // gpt-6 models refuse function tools on chat completions unless
                            // reasoning_effort is none
                            Map.of("reasoning_effort", "none")),
                    new Vendor(
                            "openai-responses",
                            "openai-responses",
                            List.of("OPENAI_API_KEY"),
                            "https://api.openai.com",
                            "/v1",
                            "gpt-6-luna",
                            "gpt-6-luna",
                            c -> new OpenAiResponsesProvider(ResponsesDialect.OPENAI, c),
                            true,
                            Map.of()),
                    new Vendor(
                            "xai",
                            "xai",
                            List.of("XAI_API_KEY"),
                            "https://api.x.ai",
                            "/v1",
                            "grok-4.20-0309-non-reasoning",
                            "grok-4.3",
                            c -> new OpenAiCompatibleProvider(Dialect.XAI, c),
                            true,
                            Map.of()),
                    new Vendor(
                            "perplexity",
                            "perplexity-agent",
                            List.of("PERPLEXITY_API_KEY"),
                            "https://api.perplexity.ai",
                            "/v1",
                            "openai/gpt-6-luna",
                            "openai/gpt-6-luna",
                            c -> new OpenAiResponsesProvider(ResponsesDialect.PERPLEXITY, c),
                            true,
                            Map.of()),
                    new Vendor(
                            "anthropic",
                            "anthropic",
                            List.of("ANTHROPIC_API_KEY", "SPIKE_ANTHROPIC_KEY"),
                            "https://api.anthropic.com",
                            "",
                            "claude-haiku-5-5",
                            "claude-haiku-5-5",
                            AnthropicProvider::new,
                            true,
                            Map.of()),
                    new Vendor(
                            "gemini",
                            "gemini",
                            List.of("GEMINI_API_KEY", "SMOKE_GEMINI_API_KEY"),
                            "https://generativelanguage.googleapis.com",
                            "",
                            "gemini-3.7-flash",
                            "gemini-3.7-flash",
                            GoogleProvider::new,
                            true,
                            Map.of()),
                    new Vendor(
                            "kimi",
                            "kimi",
                            List.of("MOONSHOT_API_KEY"),
                            "https://api.moonshot.ai",
                            "/v1",
                            "kimi-k2.6",
                            "kimi-k2.6",
                            c -> new OpenAiCompatibleProvider(Dialect.KIMI, c),
                            false,
                            Map.of()),
                    new Vendor(
                            "qwen",
                            "qwen",
                            List.of("DASHSCOPE_API_KEY"),
                            "https://dashscope-intl.aliyuncs.com",
                            "/compatible-mode/v1",
                            "qwen3.8-flash",
                            "qwen3.8-flash",
                            c -> new OpenAiCompatibleProvider(Dialect.QWEN, c),
                            false,
                            Map.of()));

    /** What happened in one step. */
    private record Step(String name, boolean ok, String detail) {}

    private static String key(Vendor v) {
        for (String variable : v.keyVariables()) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    @Test
    void everyVendorWithAKeyAnswersAndItsWireFormatsStillParse() throws Exception {
        List<String> only =
                List.of(System.getenv().getOrDefault("SMOKE_ONLY", "").split(",")).stream()
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .toList();
        double cap = Double.parseDouble(System.getenv().getOrDefault("SMOKE_CAP_USD", "0.25"));
        boolean record =
                Boolean.parseBoolean(System.getenv().getOrDefault("SMOKE_RECORD", "false"));
        Path out = Path.of("target/smoke");
        Files.createDirectories(out);
        StringBuilder summary =
                new StringBuilder("# Provider smoke run, ").append(LocalDate.now()).append("\n\n");
        List<String> failures = new ArrayList<>();
        int ran = 0;
        for (Vendor v : VENDORS) {
            if (!only.isEmpty() && !only.contains(v.id())) continue;
            String key = key(v);
            if (key == null) {
                summary.append("- **")
                        .append(v.id())
                        .append("**: skipped (no key: ")
                        .append(String.join(" or ", v.keyVariables()))
                        .append(")\n");
                continue;
            }
            if (!v.priced()) {
                summary.append("- **")
                        .append(v.id())
                        .append(
                                "**: skipped (no price known, so the spend cap would protect nothing)\n");
                continue;
            }
            ran++;
            SpendGuard guard =
                    SpendGuard.withPrices(Path.of("src/test/resources/smoke-prices.properties"))
                            .cap(cap)
                            .maxOutputTokensPerCall(4_000);
            List<Step> steps = new ArrayList<>();
            try (RecordingProxy proxy = new RecordingProxy(v.origin())) {
                LLMConfig config =
                        LLMConfig.builder()
                                .apiKey(key)
                                .baseUrl(proxy.url() + v.pathPrefix())
                                .defaultModel(v.plainModel())
                                .timeout(java.time.Duration.ofSeconds(150))
                                .build();
                LLMProvider provider = v.provider().apply(config);
                LLMClient client = new DefaultLLMClient(provider);
                Run run = new Run(v, proxy, guard, client, key, out.resolve(v.directory()), record);
                steps.add(run.step("plain", () -> run.plain()));
                steps.add(run.step("stream", () -> run.stream()));
                steps.add(run.step("tool", () -> run.tool()));
                steps.add(run.step("tool-stream", () -> run.toolStream()));
                steps.add(run.step("reasoning-stream", () -> run.reasoningStream()));
            }
            boolean allOk = steps.stream().allMatch(Step::ok);
            if (!allOk) failures.add(v.id());
            summary.append("- **")
                    .append(v.id())
                    .append("** (")
                    .append(v.plainModel())
                    .append("): ")
                    .append(
                            steps.stream()
                                    .map(
                                            s ->
                                                    s.name()
                                                            + (s.ok()
                                                                    ? " ok"
                                                                    : " FAILED ("
                                                                            + s.detail()
                                                                            + ")"))
                                    .collect(Collectors.joining("; ")))
                    .append(
                            String.format(
                                    Locale.ROOT,
                                    ". %d calls, %d tokens in, %d out, $%.4f spent of a $%.2f cap%s.\n",
                                    guard.calls(),
                                    guard.tokensIn(),
                                    guard.tokensOut(),
                                    guard.spentUsd(),
                                    cap,
                                    guard.stopped() ? " (STOPPED: " + guard.reason() + ")" : ""));
        }
        Files.writeString(out.resolve("SUMMARY.md"), summary.toString(), StandardCharsets.UTF_8);
        System.out.println(summary);
        assumeFalse(ran == 0, "no vendor key is set (see the class comment)");
        if (!failures.isEmpty())
            throw new AssertionError("smoke failures: " + failures + "\n" + summary);
    }

    /** The steps for one vendor. */
    private static final class Run {
        private final Vendor v;
        private final RecordingProxy proxy;
        private final SpendGuard guard;
        private final LLMClient client;
        private final String key;
        private final Path dir;
        private final boolean record;

        Run(
                Vendor v,
                RecordingProxy proxy,
                SpendGuard guard,
                LLMClient client,
                String key,
                Path dir,
                boolean record) {
            this.v = v;
            this.proxy = proxy;
            this.guard = guard;
            this.client = client;
            this.key = key;
            this.dir = dir;
            this.record = record;
        }

        Step step(String name, ThrowingRunnable body) {
            int from = proxy.mark();
            try {
                body.run();
                save(name, from);
                return new Step(name, true, "");
            } catch (Exception | AssertionError e) {
                save(name + "-failed", from);
                return new Step(
                        name,
                        false,
                        scrubbed(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        private String scrubbed(String text) {
            return Scrub.body(text == null ? "" : text, List.of(key)).replaceAll("\\s+", " ");
        }

        private LLMClient model(String model) {
            return guard.guard(client, model);
        }

        private void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        void plain() {
            LLMResponse r =
                    model(v.plainModel())
                            .chat(
                                    LLMRequest.builder()
                                            .model(v.plainModel())
                                            .addUserMessage("Reply with exactly the word: ready")
                                            .maxTokens(600)
                                            .build());
            check(
                    r.getContent() != null
                            && r.getContent().toLowerCase(Locale.ROOT).contains("ready"),
                    "the answer was not 'ready': " + r.getContent());
            check(
                    r.getTokenUsage() != null && r.getTokenUsage().getTotalTokens() > 0,
                    "no token usage");
            check(
                    r.getFinishReason() == LLMResponse.FinishReason.STOP,
                    "finish reason " + r.getFinishReason());
        }

        void stream() {
            List<LLMResponse> chunks =
                    model(v.plainModel())
                            .chatStream(
                                    LLMRequest.builder()
                                            .model(v.plainModel())
                                            .addUserMessage("Reply with exactly the word: ready")
                                            .maxTokens(600)
                                            .build())
                            .toList();
            String text =
                    chunks.stream()
                            .map(LLMResponse::getContent)
                            .filter(c -> c != null)
                            .collect(Collectors.joining());
            check(
                    text.toLowerCase(Locale.ROOT).contains("ready"),
                    "the streamed answer was not 'ready': " + text);
            LLMResponse last = chunks.get(chunks.size() - 1);
            check(last.getTokenUsage() != null, "no usage in the final chunk");
            check(
                    last.getFinishReason() == LLMResponse.FinishReason.STOP,
                    "final finish reason " + last.getFinishReason());
        }

        private LLMRequest weather() {
            return LLMRequest.builder()
                    .model(v.plainModel())
                    .addUserMessage(
                            "What is the weather in Pune right now? Use the get_weather tool.")
                    .tools(List.of(WEATHER))
                    .maxTokens(800)
                    .additionalParameters(v.toolParameters())
                    .build();
        }

        void tool() {
            LLMClient c = model(v.plainModel());
            LLMResponse first = c.chat(weather());
            check(first.hasToolCalls(), "no tool call: " + first.getContent());
            ToolCall call = first.getToolCalls().get(0);
            check(
                    call.name().equals("get_weather")
                            && String.valueOf(call.arguments().get("city"))
                                    .toLowerCase(Locale.ROOT)
                                    .contains("pune"),
                    "unexpected call " + call);
            check(
                    first.getFinishReason() == LLMResponse.FinishReason.TOOL_CALLS,
                    "finish reason " + first.getFinishReason());
            LLMResponse second =
                    c.chat(
                            LLMRequest.builder()
                                    .model(v.plainModel())
                                    .addUserMessage(
                                            "What is the weather in Pune right now? Use the get_weather tool.")
                                    .addMessage(
                                            Message.assistantToolCalls(
                                                    first.getContent(),
                                                    first.getToolCalls(),
                                                    first.getProviderData()))
                                    .addMessage(
                                            Message.toolResult(
                                                    call.id(),
                                                    call.name(),
                                                    "{\"temperature_c\":31,\"sky\":\"clear\"}"))
                                    .tools(List.of(WEATHER))
                                    .maxTokens(800)
                                    .additionalParameters(v.toolParameters())
                                    .build());
            check(
                    second.getContent() != null && !second.getContent().isBlank(),
                    "no answer after the tool result");
            check(
                    second.getContent().contains("31"),
                    "the answer ignores the tool result: " + second.getContent());
        }

        void toolStream() {
            List<LLMResponse> chunks = model(v.plainModel()).chatStream(weather()).toList();
            List<ToolCallDelta> deltas =
                    chunks.stream()
                            .map(LLMResponse::getToolCallDelta)
                            .filter(d -> d != null)
                            .toList();
            check(!deltas.isEmpty(), "no tool-call deltas");
            check("get_weather".equals(deltas.get(0).name()), "first delta " + deltas.get(0));
            String args =
                    deltas.stream()
                            .map(ToolCallDelta::argumentsFragment)
                            .collect(Collectors.joining());
            try {
                check(
                        JSON.readTree(args)
                                .path("city")
                                .asText()
                                .toLowerCase(Locale.ROOT)
                                .contains("pune"),
                        "arguments " + args);
            } catch (IOException e) {
                throw new AssertionError("arguments are not JSON: " + args);
            }
            check(
                    chunks.get(chunks.size() - 1).getFinishReason()
                            == LLMResponse.FinishReason.TOOL_CALLS,
                    "final finish reason");
        }

        void reasoningStream() {
            List<LLMResponse> chunks =
                    model(v.reasoningModel())
                            .chatStream(
                                    LLMRequest.builder()
                                            .model(v.reasoningModel())
                                            .addUserMessage(
                                                    "What is 17 * 23? Think it through, then give the number.")
                                            .reasoning(true)
                                            .maxTokens(1500)
                                            .build())
                            .toList();
            String text =
                    chunks.stream()
                            .map(LLMResponse::getContent)
                            .filter(c -> c != null)
                            .collect(Collectors.joining());
            String thought =
                    chunks.stream()
                            .map(LLMResponse::getReasoning)
                            .filter(c -> c != null)
                            .collect(Collectors.joining());
            check(text.contains("391"), "the answer does not contain 391: " + text);
            check(!text.contains(thought) || thought.isEmpty(), "reasoning leaked into the answer");
            System.out.println(
                    "[" + v.id() + "] reasoning text received: " + thought.length() + " chars");
        }

        /**
         * Writes the exchanges since {@code from}: raw to target/smoke, scrubbed fixtures to the
         * test resources when recording.
         */
        private void save(String name, int from) {
            List<RecordingProxy.Exchange> all = proxy.exchanges();
            try {
                Files.createDirectories(dir);
                for (int i = from; i < all.size(); i++) {
                    RecordingProxy.Exchange e = all.get(i);
                    String suffix = all.size() - from > 1 ? "-" + (i - from + 1) : "";
                    String ext =
                            e.contentType() != null && e.contentType().contains("event-stream")
                                    ? "sse"
                                    : "json";
                    Files.writeString(
                            dir.resolve(name + suffix + ".request.json"),
                            Scrub.body(e.requestBody(), List.of(key)),
                            StandardCharsets.UTF_8);
                    Files.writeString(
                            dir.resolve(name + suffix + "." + ext),
                            Scrub.body(e.responseBody(), List.of(key)),
                            StandardCharsets.UTF_8);
                    if (record && !name.endsWith("-failed") && e.status() == 200)
                        fixture(name + suffix, e);
                }
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }

        private void fixture(String name, RecordingProxy.Exchange e) throws IOException {
            Path target =
                    Path.of("../ai-agent4j/src/test/resources/providers").resolve(v.directory());
            Files.createDirectories(target);
            String model = modelOf(e);
            String body = Scrub.body(e.responseBody(), List.of(key));
            String prompt = promptOf(e);
            String header =
                    "# source: recorded\n# vendor: "
                            + v.id()
                            + "\n# date: "
                            + LocalDate.now()
                            + "\n# model: "
                            + model
                            + "\n# endpoint: "
                            + e.method()
                            + " "
                            + URI.create("http://x" + e.path()).getPath()
                            + "\n# step: "
                            + name
                            + "\n# prompt: "
                            + prompt
                            + "\n# note: recorded by ProviderSmokeIntegrationTest; keys, account ids and request ids scrubbed\n---\n";
            Files.writeString(
                    target.resolve(name + ".fixture"), header + body, StandardCharsets.UTF_8);
        }

        private String modelOf(RecordingProxy.Exchange e) {
            try {
                JsonNode request = JSON.readTree(e.requestBody());
                return request.path("model").asText(v.plainModel());
            } catch (IOException ex) {
                return v.plainModel();
            }
        }

        private String promptOf(RecordingProxy.Exchange e) {
            try {
                JsonNode request = JSON.readTree(e.requestBody());
                JsonNode messages =
                        request.has("messages") ? request.get("messages") : request.path("input");
                String last = "";
                if (messages.isArray()) {
                    for (JsonNode m : messages) {
                        if ("user".equals(m.path("role").asText()) && m.path("content").isTextual())
                            last = m.path("content").asText();
                    }
                }
                return last.replace('\n', ' ');
            } catch (IOException ex) {
                return "";
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @SuppressWarnings("unused")
    private static Map<String, String> unused() {
        return new LinkedHashMap<>();
    }
}
