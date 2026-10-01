package io.github.llm4j.loom.generic.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.Declared;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcurrencyTest {

    @TempDir
    Path dir;
    MockWebServer server;
    final List<String> bodies = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                bodies.add(r.getBody().readUtf8());
                return new MockResponse().setBody("ok");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @RepeatedTest(5)
    @Tag("C1")
    @Tag("C5")
    void thirtyTwoThreadsOnOneWebhookToolSendThirtyTwoDistinctMessagesAndRecordThirtyTwoEffects() throws Exception {
        RecordingEffects ctx = new RecordingEffects();
        Tool tool = new Declared(Map.of("HOOK", server.url("/hook").toString()), dir)
                .create("tool Hook { use: webhook  url: env.HOOK  format: json }", ctx);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 32; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    tool.execute(Map.of("text", "message " + n));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(server.getRequestCount()).isEqualTo(32);
        assertThat(bodies).doesNotHaveDuplicates();
        assertThat(ctx.journal().all().values()).hasSize(32).extracting(io.github.llm4j.tools.EffectJournal.Entry::kind).containsOnly("effect_done");
    }

    static final String SCRIPT = """
            tool Notify { use: webhook  url: env.HOOK  format: json }
            tool Log    { use: file  root: "out"  mode: write }
            agent Worker { model: "m" system: "You are Worker." tools: [Notify, Log]  max_iterations: 10 }
            agent Planner { model: "m" system: "You are Planner." output_schema: { tasks: list<{ id: string }> } }
            workflow Main() {
                delegate "plan the batch" to Planner -> plan
                parallel for each item in plan.tasks {
                    delegate "handle {item.id}" to Worker -> {item.id}
                }
            }
            """;
    static final int ITEMS = 20;
    static final Pattern HANDLE = Pattern.compile("Current Task:\\s*handle (\\w+)");

    /** Each worker notifies, logs, then finishes: decided from how many observations are already in the task. */
    static String worker(io.github.llm4j.model.LLMRequest request) {
        String task = ScriptedRun.lastMessage(request);
        String system = request.getMessages().get(0).getContent();
        if (system.contains("You are Planner")) return "```json\n" + batch(ITEMS) + "\n```";
        Matcher m = HANDLE.matcher(task);
        if (!m.find()) return ScriptedRun.done("ok");
        String id = m.group(1);
        int observed = task.split("Observation:", -1).length - 1;
        return switch (observed) {
            case 0 -> ScriptedRun.call("Notify", "{\"text\": \"item " + id + " done\"}");
            case 1 -> ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"log.md\", \"content\": \"handled " + id + "\"}");
            default -> ScriptedRun.done("finished " + id);
        };
    }

    static String batch(int n) {
        StringBuilder sb = new StringBuilder("{\"tasks\": [");
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : ",").append("{\"id\": \"i").append(i).append("\"}");
        return sb.append("]}").toString();
    }

    ScriptedRun run(RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = ConcurrencyTest::worker;
        run.journal = journal;
        run.env.put("HOOK", server.url("/hook").toString());
        return run;
    }

    void execute(ScriptedRun run) {
        HarnessExecutor executor = run.executor(SCRIPT);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

    }

    @RepeatedTest(5)
    @Tag("C4")
    @Tag("C5")
    void aParallelForEachActsOnceForEveryItemAndAResumeRepeatsNothing() throws Exception {
        RunJournal journal = RunJournal.inMemory();
        Path log = dir.resolve("out/log.md");

        execute(run(journal));

        assertThat(server.getRequestCount()).isEqualTo(20);
        assertThat(bodies).doesNotHaveDuplicates();
        List<String> lines = Files.readAllLines(log);
        assertThat(lines).hasSize(20).doesNotHaveDuplicates();
        Set<String> ids = new HashSet<>();
        lines.forEach(l -> ids.add(l.replace("handled ", "")));
        assertThat(ids).hasSize(20);

        // The same run, resumed on the same journal: every step and every effect is already done.
        execute(run(journal));
        assertThat(server.getRequestCount()).isEqualTo(20);
        assertThat(Files.readAllLines(log)).hasSize(20);
    }
}
