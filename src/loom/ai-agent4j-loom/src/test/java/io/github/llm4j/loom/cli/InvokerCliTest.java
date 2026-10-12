package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code weave run --invoker-env}, {@code weave check} and a resumed run, for a script that acts as the person running it. */
class InvokerCliTest {

    private static final String SCRIPT = """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
            workflow Main() {
                delegate "look" to Scout -> first
                human_prompt "Go on?" -> answer
                delegate "again" to Scout -> second
            }
            """;

    @TempDir
    Path dir;

    MockWebServer api = new MockWebServer();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final AtomicInteger modelCalls = new AtomicInteger();
    File script;

    @BeforeEach
    void start() throws Exception {
        api.start();
        Files.writeString(dir.resolve("refunds.json"), """
                {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"}, "servers": [{"url": "%s"}],
                 "paths": {"/refunds": {"get": {"summary": "List", "responses": {"200": {"description": "ok"}}}}}}
                """.formatted(api.url("/").toString().replaceAll("/$", "")));
        script = dir.resolve("job.loom").toFile();
        Files.writeString(script.toPath(), SCRIPT);
    }

    @AfterEach
    void stop() throws Exception {
        api.shutdown();
    }

    /** A model that calls the tool the first time each agent task is asked, then answers. */
    private final io.github.llm4j.loom.execution.LLMClientFactory models = name -> new LLMClient() {
        @Override
        public LLMResponse chat(LLMRequest r) {
            modelCalls.incrementAndGet();
            boolean observed = r.getMessages().stream().anyMatch(m -> m.getContent().contains("Observation:"));
            String content = observed
                    ? "```json\n{\"thought\": \"t\", \"final_answer\": \"ok\"}\n```"
                    : "```json\n{\"thought\": \"t\", \"action\": \"Refunds\", \"action_input\": {\"endpoint\": \"/refunds\", \"method\": \"GET\"}}\n```";
            return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).build();
        }

        @Override
        public Stream<LLMResponse> chatStream(LLMRequest r) {
            return Stream.of(chat(r));
        }
    };

    private WeaveEnv env(Map<String, String> variables, HumanInterface human) {
        Function<String, String> lookup = variables::get;
        return new WeaveEnv(models, human, new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), lookup);
    }

    private static WeaveEnv withInvokerEnv(WeaveEnv env, String... pairs) {
        InvokerOptions options = new InvokerOptions();
        for (int i = 0; i < pairs.length; i += 2) options.variables.put(pairs[i], pairs[i + 1]);
        return options.apply(env);
    }

    private static final HumanInterface AWAY = new HumanInterface() {
        @Override
        public String promptHuman(String message) {
            throw new IllegalStateException("a step id is needed");
        }

        @Override
        public String promptHuman(String stepId, String message) {
            throw new RunSuspended(stepId, message);
        }
    };

    @Test
    @Tag("CC-V1.8")
    void checkSaysWhichCredentialsTheScriptTakesFromWhoRunsIt() {
        int exit = WeaveCLI.check(script, null, false, env(Map.of(), m -> "yes"));

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("acts as the person who runs it, with their token").contains("--invoker-env NAME=VARIABLE")
                .contains("ready to run");
    }

    @Test
    @Tag("CC-V1.8")
    void checkSaysNothingAboutItWhenTheScriptDoesNotAskForAny() throws Exception {
        Files.writeString(script.toPath(), "agent Scout { model: \"m\" system: \"You are Scout.\" }\nworkflow Main() { delegate \"x\" to Scout -> r }");

        WeaveCLI.check(script, null, false, env(Map.of(), m -> "yes"));

        assertThat(out.toString()).doesNotContain("acts as the person");
    }

    @Test
    @Tag("CC-V6.3")
    void aRunStartedWithoutTheCredentialsStopsAndSaysWhichAreNeeded() {
        int exit = WeaveCLI.run(script, null, "Main", Map.of(), null, null, null, null, null, null, false, env(Map.of(), m -> "yes"));

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("needs invoker credentials [token]").contains("InvokerCredentials");
        assertThat(modelCalls).hasValue(0);
        assertThat(api.getRequestCount()).isZero();
    }

    @Test
    @Tag("CC-V6.4")
    void invokerEnvTakesTheCredentialFromAnEnvironmentVariable() {
        WeaveEnv env = withInvokerEnv(env(Map.of("MY_TOKEN", "Bearer from-env"), m -> "go"), "token", "MY_TOKEN");
        api.enqueue(new MockResponse().setBody("[]"));
        api.enqueue(new MockResponse().setBody("[]"));

        int exit = WeaveCLI.run(script, null, "Main", Map.of(), null, null, null, null, null, null, false, env);

        assertThat(exit).isZero();
        assertThat(requests()).extracting(r -> r.getHeader("Authorization")).containsExactly("Bearer from-env", "Bearer from-env");
        assertThat(out.toString() + err).doesNotContain("from-env");
    }

    private List<RecordedRequest> requests() {
        return java.util.stream.IntStream.range(0, api.getRequestCount()).mapToObj(i -> {
            try {
                return api.takeRequest();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    @Test
    @Tag("CC-V6.4")
    void anUnsetVariableStopsTheRunAtItsStartNamingTheCredentialNotTheValue() {
        WeaveEnv env = withInvokerEnv(env(Map.of(), m -> "go"), "token", "MY_TOKEN");

        int exit = WeaveCLI.run(script, null, "Main", Map.of(), null, null, null, null, null, null, false, env);

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("[token]");
        assertThat(modelCalls).hasValue(0);
    }

    @Test
    @Tag("CC-V6.4")
    void badInvokerEnvOptionsAreRefusedWithAReason() {
        assertThat(withInvokerEnv(env(Map.of(), m -> "go"), "1bad", "X")).isNull();
        assertThat(err.toString()).contains("--invoker-env 1bad=X").contains("a name is a letter");
        err.reset();
        assertThat(withInvokerEnv(env(Map.of(), m -> "go"), "token", " ")).isNull();
        assertThat(err.toString()).contains("--invoker-env token needs the name of an environment variable");
        WeaveEnv plain = env(Map.of(), m -> "go");
        assertThat(new InvokerOptions().apply(plain)).isSameAs(plain);
        assertThat(plain.invoker()).isNull();
    }

    @Test
    @Tag("CC-V6.4")
    void theCredentialsReadTheVariableEachTimeAndShowOnlyNames() {
        java.util.Map<String, String> variables = new java.util.concurrent.ConcurrentHashMap<>();
        WeaveEnv env = withInvokerEnv(env(variables, m -> "go"), "token", "MY_TOKEN");
        InvokerCredentials invoker = env.invoker();

        assertThat(invoker.provides("token")).isFalse();
        variables.put("MY_TOKEN", "Bearer one");
        assertThat(invoker.provides("token")).isTrue();
        assertThat(invoker.get("token")).isEqualTo("Bearer one");
        variables.put("MY_TOKEN", "Bearer two");
        assertThat(invoker.get("token")).isEqualTo("Bearer two");
        variables.put("MY_TOKEN", "  ");
        assertThat(invoker.get("token")).isNull();
        assertThat(invoker.get("other")).isNull();
        assertThat(invoker.toString()).contains("token").doesNotContain("Bearer").doesNotContain("MY_TOKEN");
    }

    @Test
    @Tag("CC-V6.3")
    @Tag("CC-V5.2")
    void aResumedRunNeedsTheCredentialsAgainAndUsesTheNewOnes() {
        Path runDir = dir.resolve("runs/job-1");
        Path store = dir.resolve("store");
        api.enqueue(new MockResponse().setBody("[]"));
        WeaveEnv first = withInvokerEnv(env(Map.of("MY_TOKEN", "Bearer morning"), AWAY), "token", "MY_TOKEN");
        assertThat(WeaveCLI.run(script, null, "Main", Map.of(), null, null, null, null, runDir, store, false, first)).isEqualTo(4);

        // a fresh process that was not given the credential: the run stops and says what it needs
        assertThat(WeaveCLI.resume(runDir, env(Map.of(), m -> "go"))).isEqualTo(1);
        assertThat(err.toString()).contains("needs invoker credentials [token]");

        // given a new one, the rest of the run is done with it, and the step that finished is not run again
        api.enqueue(new MockResponse().setBody("[]"));
        WeaveEnv later = withInvokerEnv(env(Map.of("MY_TOKEN", "Bearer evening"), m -> "go"), "token", "MY_TOKEN");
        assertThat(WeaveCLI.resume(runDir, later)).isZero();

        assertThat(requests()).extracting(r -> r.getHeader("Authorization")).containsExactly("Bearer morning", "Bearer evening");
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (java.io.IOException e) {
                    return "";
                }
            }).collect(java.util.stream.Collectors.joining("\n"))).doesNotContain("morning").doesNotContain("evening");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
