package io.github.llm4j.loom.generic.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.generic.support.FaultJournal;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.LLMRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real files in samples/digest, run end to end over a scripted model and a stand-in Hacker News. */
class DigestSampleTest {

    static final Path SAMPLES = Path.of("samples/digest");

    @TempDir
    Path dir;
    MockWebServer news;
    MockWebServer slack;
    final AtomicInteger day = new AtomicInteger(1);

    @BeforeEach
    void start() throws IOException {
        news = new MockWebServer();
        news.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = String.valueOf(r.getPath());
                if (path.equals("/v0/topstories.json")) return json("[101,102]");
                if (path.equals("/v0/item/101.json")) return json("{\"id\":101,\"title\":\"Alpha story, day " + day.get() + "\"}");
                return new MockResponse().setResponseCode(404).setBody("not allowed here");
            }
        });
        news.start();
        slack = new MockWebServer();
        for (int i = 0; i < 40; i++) slack.enqueue(new MockResponse().setBody("ok"));
        slack.start();
    }

    @AfterEach
    void stop() throws IOException {
        news.shutdown();
        slack.shutdown();
    }

    static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    /** What each agent says next, from its role and how many tool results are already in its task. */
    static String reply(LLMRequest request) {
        String system = request.getMessages().get(0).getContent();
        String task = ScriptedRun.lastMessage(request);
        int seen = task.split("Observation:", -1).length - 1;
        if (system.contains("You are Collector")) {
            return switch (seen) {
                case 0 -> ScriptedRun.call("Notes", "{\"action\": \"read\", \"path\": \"last.json\"}");
                case 1 -> ScriptedRun.call("Hn", "{\"path\": \"/v0/topstories.json\"}");
                case 2 -> ScriptedRun.call("Hn", "{\"path\": \"/v0/item/101.json\"}");
                default -> ScriptedRun.done("NEW: " + title(task));
            };
        }
        if (system.contains("You are Writer")) {
            String stories = title(task);
            return switch (seen) {
                case 0 -> ScriptedRun.call("Notes", "{\"action\": \"write\", \"path\": \"digest.md\", \"content\": \"# Digest\\n" + stories + "\"}");
                case 1 -> ScriptedRun.call("Notes", "{\"action\": \"write\", \"path\": \"last.json\", \"content\": \"{\\\"reported\\\": [\\\"" + stories + "\\\"]}\"}");
                default -> ScriptedRun.done("Digest: " + stories);
            };
        }
        if (system.contains("You are Notifier")) {
            return seen == 0 ? ScriptedRun.call("Outbox", "{\"subject\": \"Daily digest\", \"body\": \"" + title(task) + "\"}") : ScriptedRun.done("sent");
        }
        if (system.contains("You are Pinger")) {
            return seen == 0 ? ScriptedRun.call("Slack", "{\"title\": \"Digest\", \"text\": \"" + title(task) + "\"}") : ScriptedRun.done("posted");
        }
        return ScriptedRun.done("ok");
    }

    /** "Alpha story, day N" from wherever it is in the task. */
    static String title(String task) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Alpha story, day \\d+").matcher(task);
        String last = "nothing new";
        while (m.find()) last = m.group();
        return last;
    }

    ScriptedRun run(RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = DigestSampleTest::reply;
        run.journal = journal;
        run.env.put("HN_URL", news.url("/").toString().replaceAll("/$", ""));
        run.env.put("SLACK_WEBHOOK", slack.url("/services/T000/B000/hook").toString());
        return run;
    }

    HarnessExecutor executor(ScriptedRun run, String script) throws IOException {
        HarnessExecutor executor = run.executor(new LoomLoader().load(SAMPLES.resolve(script).toString()));
        executor.initialize();
        return executor;
    }

    long emlFiles() throws IOException {
        Path outbox = dir.resolve("outbox");
        if (!Files.isDirectory(outbox)) return 0;
        try (Stream<Path> files = Files.list(outbox)) {
            return files.filter(p -> p.toString().endsWith(".eml")).count();
        }
    }

    @Test
    @Tag("V10.3")
    void twoDaysCollectWriteAndMailAndTheSecondDayReadsTheFirstDaysState() throws Exception {
        // Day 1
        ScriptedRun first = run(RunJournal.inMemory());
        executor(first, "digest.loom").executeWorkflow("DailyDigest", Map.of("topic", "AI agents"));

        assertThat(Files.readString(dir.resolve("digest/digest.md"))).contains("Alpha story, day 1");
        assertThat(Files.readString(dir.resolve("digest/last.json"))).contains("Alpha story, day 1");
        assertThat(emlFiles()).isEqualTo(1);
        assertThat(news.getRequestCount()).isEqualTo(2);
        assertThat(first.seen()).as("day 1 found no earlier state").contains("doesn't exist");

        // Day 2: a new run, a new journal; only the files are shared.
        day.set(2);
        ScriptedRun second = run(RunJournal.inMemory());
        executor(second, "digest.loom").executeWorkflow("DailyDigest", Map.of("topic", "AI agents"));

        assertThat(second.seen()).as("day 2 read what day 1 reported").contains("{\"reported\": [\"Alpha story, day 1\"]}");
        assertThat(Files.readString(dir.resolve("digest/digest.md"))).contains("Alpha story, day 2").doesNotContain("day 1");
        assertThat(Files.readString(dir.resolve("digest/last.json"))).contains("day 2");
        assertThat(emlFiles()).isEqualTo(2);
        for (ScriptedRun r : List.of(first, second)) assertThat(r.audit).contains("tool_effect", "tool_call");
    }

    @Test
    @Tag("V10.3")
    void theSampleOnlyEverAsksTheNewsServerForTheTwoPathsItAllows() throws Exception {
        ScriptedRun run = run(RunJournal.inMemory());
        run.responder = request -> {
            String system = request.getMessages().get(0).getContent();
            if (!system.contains("You are Collector")) return reply(request);
            int seen = ScriptedRun.lastMessage(request).split("Observation:", -1).length - 1;
            return seen == 0 ? ScriptedRun.call("Hn", "{\"path\": \"/v0/updates.json\"}") : ScriptedRun.done("nothing");
        };
        executor(run, "digest.loom").executeWorkflow("DailyDigest", Map.of("topic", "x"));
        assertThat(news.getRequestCount()).as("a path outside allow_paths is refused before any request").isZero();
    }

    @Test
    @Tag("V10.4")
    void aCrashAnywhereInTheSlackVariantNotifiesOnceAndMailsOnce() throws Exception {
        for (int writesBeforeCrash = 0; writesBeforeCrash <= 24; writesBeforeCrash++) {
            Path base = Files.createTempDirectory(dir, "sweep");
            RunJournal storage = RunJournal.inMemory();
            int slackBefore = slack.getRequestCount();
            for (RunJournal journal : List.of(new FaultJournal(storage, writesBeforeCrash, false), storage)) {
                ScriptedRun run = new ScriptedRun(base);
                run.responder = DigestSampleTest::reply;
                run.journal = journal;
                run.env.put("HN_URL", news.url("/").toString().replaceAll("/$", ""));
                run.env.put("SLACK_WEBHOOK", slack.url("/services/T000/B000/hook").toString());
                try {
                    HarnessExecutor executor = run.executor(new LoomLoader().load(SAMPLES.resolve("digest-slack.loom").toString()));
                    executor.setBaseDir(base);
                    executor.initialize();
                    executor.executeWorkflow("DailyDigestWithSlack", Map.of("topic", "AI agents"));
                } catch (FaultJournal.SimulatedCrash crashed) {
                    // the process died at this journal write
                }
            }

            Set<String> keys = new HashSet<>();
            for (int i = slackBefore; i < slack.getRequestCount(); i++) keys.add(slack.takeRequest().getHeader("Idempotency-Key"));
            assertThat(keys).as("distinct Slack messages after a crash at journal write " + writesBeforeCrash).hasSize(1);
            Path outbox = base.resolve("outbox");
            try (Stream<Path> files = Files.list(outbox)) {
                assertThat(files.filter(p -> p.toString().endsWith(".eml")).count()).as("emails after a crash at write " + writesBeforeCrash).isEqualTo(1);
            }
        }
    }
}
