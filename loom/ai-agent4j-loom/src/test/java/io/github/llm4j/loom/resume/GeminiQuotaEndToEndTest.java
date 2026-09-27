package io.github.llm4j.loom.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.JdbcTriggerStore;
import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerEndpoint;
import io.github.llm4j.loom.trigger.TriggerRunner;
import io.github.llm4j.loom.trigger.TriggerTarget;
import io.github.llm4j.provider.google.GoogleProvider;
import java.time.Instant;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * Verification plan E2E-1: a Gemini daily quota, end to end — the real GoogleProvider and HTTP layer
 * against a mock Gemini, a SQL journal and SQL trigger store, and the HTTP tick a cloud scheduler calls.
 */
class GeminiQuotaEndToEndTest {

    static final String DAILY_429 = """
            {"error": {"code": 429, "message": "You exceeded your current quota", "status": "RESOURCE_EXHAUSTED",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                 "violations": [{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "20s"}]}}
            """;

    static String answer(String text) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            String react = "```json\n" + json.writeValueAsString(Map.of("thought", "t", "final_answer", text)) + "\n```";
            return json.writeValueAsString(Map.of(
                    "candidates", java.util.List.of(Map.of(
                            "content", Map.of("parts", java.util.List.of(Map.of("text", react)), "role", "model"),
                            "finishReason", "STOP")),
                    "usageMetadata", Map.of("promptTokenCount", 100, "candidatesTokenCount", 50, "totalTokenCount", 150)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String SCRIPT = """
            agent Researcher { model: "gemini-2.5-flash" system: "You are Researcher." }
            agent Drafter { model: "gemini-2.5-flash" system: "You are Drafter." }
            workflow Main() {
                delegate "research" to Researcher -> research
                delegate "draft from {research}" to Drafter -> draft
            }
            """;

    @Test
    void e2e1_dailyQuotaPausesUntilPacificMidnightAndACloudTickResumes() throws Exception {
        TestClock clock = new TestClock();
        try (MockWebServer gemini = new MockWebServer()) {
            gemini.start();
            gemini.enqueue(new MockResponse().setBody(answer("findings")));
            gemini.enqueue(new MockResponse().setResponseCode(429).setBody(DAILY_429));
            gemini.enqueue(new MockResponse().setBody(answer("the draft")));

            org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
            db.setURL("jdbc:h2:mem:e2e1;DB_CLOSE_DELAY=-1");
            JdbcRunJournal.createTable(db);
            JdbcTriggerStore.createTable(db);
            JdbcTriggerStore triggers = new JdbcTriggerStore(db);

            java.util.function.Function<String, LLMConfig> config = model -> LLMConfig.builder().apiKey("test-key")
                    .baseUrl(gemini.url("/v1beta").toString()).defaultModel(model)
                    .retryPolicy(RetryPolicy.builder().maxRetries(3).addRetryableStatusCode(429).clock(clock)
                            .sleeper(d -> clock.advance(d)).build())
                    .build();
            String[] lastDraft = new String[1];
            // The host rebuilds the run from the database: the same code runs on first start and on every resume.
            java.util.function.Supplier<HarnessExecutor> host = () -> {
                HarnessExecutor e = new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(),
                        new ToolRegistry(), model -> new DefaultLLMClient(new GoogleProvider(config.apply(model))));
                e.setJournal(new JdbcRunJournal(db, "run-42"));
                e.setTriggerStore(triggers);
                e.setRunId("run-42");
                e.setClock(clock);
                e.initialize();
                return e;
            };

            assertThatThrownBy(() -> host.get().executeWorkflow("Main", Map.of()))
                    .isInstanceOfSatisfying(RunSuspended.class, s -> {
                        assertThat(s.reason()).isEqualTo(RunSuspended.Reason.RATE_LIMIT);
                        assertThat(s.resumeAt()).isEqualTo(Instant.parse("2026-09-28T07:00:00Z"));
                        assertThat(s.limit().scope().name()).isEqualTo("DAILY_QUOTA");
                        assertThat(s.limit().provider()).isEqualTo("localhost");
                    });
            Trigger resume = triggers.get("resume:run-42").orElseThrow();
            assertThat(resume.nextFire()).isBetween(Instant.parse("2026-09-28T07:00:00Z"), Instant.parse("2026-09-28T07:00:30Z"));
            assertThat(gemini.getRequestCount()).isEqualTo(2); // no blind retries of a daily quota

            TriggerTarget target = (t, runId) -> {
                HarnessExecutor e = host.get();
                try {
                    e.executeWorkflow("Main", Map.of());
                    lastDraft[0] = String.valueOf(e.getContext().getVariable("draft"));
                    return TriggerTarget.Outcome.done();
                } catch (RunSuspended s) {
                    return TriggerTarget.Outcome.suspended(s.resumeAt(), s.getMessage());
                }
            };
            TriggerEndpoint endpoint = new TriggerEndpoint(new TriggerRunner(triggers, target, clock, "cloud-run-1"),
                    () -> "tick-secret", null);

            clock.set("2026-09-28T06:59:00Z");
            assertThat(endpoint.handle("POST", Map.of("X-Loom-Token", "tick-secret")).body()).isEqualTo("{\"fired\":0}");
            clock.set(resume.nextFire());
            assertThat(endpoint.handle("POST", Map.of("X-Loom-Token", "tick-secret")).body()).isEqualTo("{\"fired\":1}");

            assertThat(lastDraft[0]).isEqualTo("the draft");
            assertThat(triggers.all()).isEmpty();
            assertThat(gemini.getRequestCount()).isEqualTo(3); // research once, draft twice
            assertThat(gemini.takeRequest().getBody().readUtf8()).contains("research");
        }
    }
}
