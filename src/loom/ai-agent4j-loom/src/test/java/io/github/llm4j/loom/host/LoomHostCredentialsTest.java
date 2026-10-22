package io.github.llm4j.loom.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.MissingInvokerCredentialException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Running for a person: their credentials reach the tool, are required up front, and are never
 * written down. V8.7–V8.9.
 */
class LoomHostCredentialsTest {

    static final String CANARY = "Bearer sk-canary-7f3a9c41";

    static final String SCRIPT =
            """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
            workflow Main() { delegate "refunds?" to Scout -> x }
            """;

    @TempDir Path dir;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final List<RecordedRequest> seen = Collections.synchronizedList(new ArrayList<>());

    private MockWebServer api() throws IOException {
        MockWebServer api = new MockWebServer();
        api.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        seen.add(request);
                        return new MockResponse().setBody("{\"refunds\": []}");
                    }
                });
        api.start();
        Files.writeString(
                dir.resolve("refunds.json"),
                """
                {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"},
                 "servers": [{"url": "%s"}],
                 "paths": {"/refunds": {"get": {"summary": "List refunds", "responses": {"200": {"description": "ok"}}}}}}
                """
                        .formatted(api.url("/").toString().replaceAll("/$", "")));
        return api;
    }

    /** A host whose model asks for the refunds on its first turn and answers on its second. */
    private HostEnv hostWithModel(String kind) {
        HostEnv env = new HostEnv(kind, dir.resolve("store"));
        env.host =
                LoomHost.builder(
                                HostEnv.parse(SCRIPT),
                                new ToolRegistry(),
                                model ->
                                        new LLMClient() {
                                            @Override
                                            public LLMResponse chat(LLMRequest r) {
                                                modelCalls.incrementAndGet();
                                                String all =
                                                        r.getMessages().stream()
                                                                .map(m -> m.getContent())
                                                                .reduce("", (a, b) -> a + "\n" + b);
                                                String reply =
                                                        all.contains("Observation:")
                                                                ? "```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```"
                                                                : "```json\n{\"thought\": \"look\", \"action\": \"Refunds\", \"action_input\": {\"endpoint\": \"/refunds\", \"method\": \"GET\"}}\n```";
                                                return LLMResponse.builder()
                                                        .content(reply)
                                                        .model("m")
                                                        .tokenUsage(10, 5, 15)
                                                        .build();
                                            }

                                            @Override
                                            public Stream<LLMResponse> chatStream(LLMRequest r) {
                                                return Stream.of(chat(r));
                                            }
                                        })
                        .persistence(env.persistence())
                        .baseDir(dir)
                        .envLookup(Map.<String, String>of()::get)
                        .build();
        return env;
    }

    @Tag("SB-V8.7")
    @ParameterizedTest
    @ValueSource(strings = {"file", "jdbc"})
    void theToolActsWithTheCredentialsOfThePersonWhoStartedTheRun(String kind) throws Exception {
        try (MockWebServer api = api()) {
            HostEnv env = hostWithModel(kind);
            RunOutcome out =
                    env.host.run(
                            RunRequest.of("Main", Map.of())
                                    .as(InvokerCredentials.of(Map.of("token", "Bearer u1"))));
            assertThat(out).isInstanceOf(RunOutcome.Completed.class);
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).getHeader("Authorization")).isEqualTo("Bearer u1");
        }
    }

    @Tag("SB-V8.8")
    @ParameterizedTest
    @ValueSource(strings = {"file", "jdbc"})
    void aRunThatNeedsCredentialsItWasNotGivenStopsBeforeAnyStep(String kind) throws Exception {
        try (MockWebServer api = api()) {
            HostEnv env = hostWithModel(kind);
            assertThatThrownBy(
                            () ->
                                    env.host.run(
                                            RunRequest.of("Main", Map.of())
                                                    .withRunId("nobody")
                                                    .as(InvokerCredentials.of(Map.of()))))
                    .isInstanceOf(MissingInvokerCredentialException.class);
            assertThatThrownBy(
                            () ->
                                    env.host.run(
                                            RunRequest.of("Main", Map.of()).withRunId("nobody2")))
                    .isInstanceOf(MissingInvokerCredentialException.class);
            assertThat(modelCalls.get()).isZero();
            assertThat(seen).isEmpty();
            assertThat(env.host.run("nobody").orElseThrow().detail()).contains("credentials");
            assertThat(env.host.run("nobody").orElseThrow().status())
                    .isEqualTo(RunRecord.Status.FAILED);
        }
    }

    @Tag("SB-V8.9")
    @ParameterizedTest
    @ValueSource(strings = {"file", "jdbc"})
    void credentialsAreNotWrittenAnywhere(String kind) throws Exception {
        try (MockWebServer api = api()) {
            HostEnv env = hostWithModel(kind);
            RunOutcome out =
                    env.host.run(
                            RunRequest.of("Main", Map.of("who", "ann"))
                                    .withRunId("r-secret")
                                    .as(InvokerCredentials.of(Map.of("token", CANARY))));
            assertThat(out).isInstanceOf(RunOutcome.Completed.class);
            RunRecord rec = env.host.run("r-secret").orElseThrow();
            assertThat(rec.inputs()).containsEntry("who", "ann").doesNotContainValue(CANARY);
            assertThat(everythingStored(env))
                    .as("everything the host stored")
                    .doesNotContain("sk-canary")
                    .contains("r-secret");
        }
    }

    @Tag("SB-V8.22")
    @ParameterizedTest
    @ValueSource(strings = {"file", "jdbc"})
    void aTriggerCannotResumeARunThatNeedsAPersonsCredentials(String kind) throws Exception {
        try (MockWebServer api = api()) {
            HostEnv env = hostWithModel(kind);
            java.time.Instant now = java.time.Instant.parse("2026-10-15T09:00:00Z");
            env.persistence()
                    .registry(null)
                    .save(
                            new RunRecord(
                                    "needs-creds",
                                    "Main",
                                    Map.of(),
                                    "app",
                                    RunRecord.Status.SUSPENDED,
                                    io.github.llm4j.loom.runtime.RunSuspended.Reason.SLEEP,
                                    now,
                                    "Main/s0",
                                    null,
                                    null,
                                    now,
                                    now));
            var outcome =
                    env.host
                            .triggerTarget()
                            .fire(
                                    io.github.llm4j.loom.trigger.Trigger.resume(
                                            "needs-creds", now, "wake", 0),
                                    "needs-creds");
            assertThat(outcome.status())
                    .as(outcome.message())
                    .isEqualTo(io.github.llm4j.loom.trigger.TriggerTarget.Outcome.Status.HUMAN);
            assertThat(outcome.message()).contains("credentials");
            assertThat(modelCalls.get()).isZero();
            assertThat(env.host.run("needs-creds").orElseThrow().status())
                    .isEqualTo(RunRecord.Status.SUSPENDED); // still waiting, not failed
        }
    }

    private String everythingStored(HostEnv env) throws IOException, SQLException {
        StringBuilder all = new StringBuilder();
        if (env.ds == null) {
            try (var files = Files.walk(env.dir)) {
                for (Path f : files.filter(Files::isRegularFile).toList())
                    all.append(f).append('\n').append(Files.readString(f)).append('\n');
            }
        } else {
            try (Connection c = env.ds.getConnection();
                    ResultSet tables =
                            c.getMetaData()
                                    .getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
                List<String> names = new ArrayList<>();
                while (tables.next()) names.add(tables.getString("TABLE_NAME"));
                for (String t : names) {
                    try (var st = c.createStatement();
                            ResultSet rs = st.executeQuery("SELECT * FROM " + t)) {
                        int cols = rs.getMetaData().getColumnCount();
                        while (rs.next())
                            for (int i = 1; i <= cols; i++) all.append(rs.getString(i)).append('|');
                    }
                }
            }
        }
        return all.toString();
    }
}
