package io.github.llm4j.loom.generic.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.FaultJournal;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.JdbcRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.sql.DataSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The tools inside a real run: exactly-once under a crash, approvals, guards, and what is reported. */
class EffectsEndToEndTest {

    enum Storage { MEMORY, FILE, JDBC }

    static final String SCRIPT = """
            tool Notify { use: webhook  url: env.HOOK  format: json }
            agent Notifier { model: "m" tools: [Notify] }
            workflow Main() { delegate "tell the team" to Notifier -> sent }
            """;
    static final String CALL = ScriptedRun.call("Notify", "{\"text\": \"Digest ready\"}");

    @TempDir
    Path dir;
    MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        for (int i = 0; i < 20; i++) server.enqueue(new MockResponse().setBody("ok"));
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    /** A journal factory whose instances all see the same storage, like a restarted process opening the same file or database. */
    Supplier<RunJournal> storage(Storage kind) throws Exception {
        return switch (kind) {
            case MEMORY -> {
                RunJournal shared = RunJournal.inMemory();
                yield () -> shared;
            }
            case FILE -> {
                Path file = dir.resolve("run-" + System.nanoTime() + ".json"); // one file per scenario
                yield () -> new FileRunJournal(file);
            }
            case JDBC -> {
                DataSource db = io.github.llm4j.loom.generic.support.Databases.h2("effects" + System.nanoTime());
                JdbcRunJournal.createTable(db);
                yield () -> new JdbcRunJournal(db, "run-1");
            }
        };
    }

    ScriptedRun run(RunJournal journal, String... replies) {
        ScriptedRun run = new ScriptedRun(dir).replies(replies);
        run.env.put("HOOK", server.url("/hook").toString());
        run.journal = journal;
        return run;
    }

    void attempt(ScriptedRun run, String script) {
        try {
            HarnessExecutor executor = run.executor(script);
            executor.initialize();
            executor.executeWorkflow("Main", Map.of());
        } catch (FaultJournal.SimulatedCrash crashed) {
            // the process "died"; the journal keeps whatever it had written
        }
    }

    @ParameterizedTest
    @EnumSource(Storage.class)
    @Tag("V2.2")
    @Tag("V2.3")
    @Tag("V2.10")
    void aCrashAtAnyJournalWriteSendsTheMessageExactlyOnceAcrossTheResume(Storage storage) throws Exception {
        for (int writesBeforeCrash = 0; writesBeforeCrash <= 6; writesBeforeCrash++) {
            Supplier<RunJournal> store = storage(storage);
            int before = server.getRequestCount();

            // First attempt: the process dies at the Nth journal write.
            attempt(run(new FaultJournal(store.get(), writesBeforeCrash, false), CALL, ScriptedRun.done("sent")), SCRIPT);
            // Second attempt: a fresh process, the same journal, the model asked the same again.
            attempt(run(store.get(), CALL, ScriptedRun.done("sent")), SCRIPT);

            assertThat(server.getRequestCount() - before).as("requests with a crash after " + writesBeforeCrash + " journal writes on " + storage).isEqualTo(1);
        }
    }

    @Test
    @Tag("V2.2")
    void whenTheRunFinishesAReRunOnTheSameJournalSendsNothing() throws Exception {
        RunJournal journal = RunJournal.inMemory();
        attempt(run(journal, CALL, ScriptedRun.done("sent")), SCRIPT);
        attempt(run(journal, CALL, ScriptedRun.done("sent")), SCRIPT);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Tag("V2.4")
    void aSecondDayWithItsOwnJournalSendsAgain() throws Exception {
        attempt(run(RunJournal.inMemory(), CALL, ScriptedRun.done("sent")), SCRIPT);
        attempt(run(RunJournal.inMemory(), CALL, ScriptedRun.done("sent")), SCRIPT);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @Tag("V1.11")
    void theExecutorReportsEffectsToAuditAndTraceWithoutTheMessage() throws Exception {
        ScriptedRun run = run(RunJournal.inMemory(), CALL, ScriptedRun.done("sent"));
        attempt(run, SCRIPT);

        assertThat(run.audit).contains("tool_effect");
        assertThat(run.auditData).anyMatch(d -> "webhook".equals(d.get("tool")) || "Notify".equals(d.get("tool")));
        assertThat(run.trace).anyMatch(e -> e.type().equals("tool") && e.text().contains("Notify") && e.text().contains("ok"));
        // The tool's own reports never carry the message or the destination.
        assertThat(run.auditData.toString()).doesNotContain("Digest ready").doesNotContain("/hook");
        assertThat(run.trace.stream().filter(e -> e.type().equals("tool")).toList().toString()).doesNotContain("Digest ready").doesNotContain("/hook");
        assertThat(run.journal.all().toString().replace("Digest ready\\", "")).as("only the recorded result text").doesNotContain("/hook");
    }

    @Test
    @Tag("V8.11")
    void approvalsWorkOnGenericToolsAndAreJournaledSoAResumeDoesNotAskTwice() throws Exception {
        String script = SCRIPT.replace("tools: [Notify]", "tools: [Notify] approve: [Notify]");
        RunJournal journal = RunJournal.inMemory();

        ScriptedRun rejected = run(journal, CALL, ScriptedRun.done("sent"));
        rejected.human = q -> "no";
        attempt(rejected, script);
        assertThat(server.getRequestCount()).isZero();
        assertThat(rejected.questions).hasSize(1);

        // The same call, resumed: the answer was journaled, so nobody is asked again, and it is still rejected.
        ScriptedRun again = run(journal, CALL, ScriptedRun.done("sent"));
        again.human = q -> "yes";
        attempt(again, script);
        assertThat(again.questions).isEmpty();
        assertThat(server.getRequestCount()).isZero();

        ScriptedRun approved = run(RunJournal.inMemory(), CALL, ScriptedRun.done("sent"));
        attempt(approved, script);
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(approved.audit).contains("approval_granted", "tool_effect");
    }

    @Test
    @Tag("V1.10")
    void aPiiGuardMasksPersonalDataInAToolResultBeforeTheModelSeesIt() throws Exception {
        Files.createDirectories(dir.resolve("notes"));
        Files.writeString(dir.resolve("notes/contact.md"), "Reach Ann at ann@example.com or 555-123-4567.");
        String script = """
                tool Notes { use: file  root: "notes" }
                agent Reader { model: "m" tools: [Notes] guard { pii: mask } }
                workflow Main() { delegate "read the contact" to Reader -> r }
                """;
        ScriptedRun run = new ScriptedRun(dir).replies(ScriptedRun.call("Notes", "{\"action\": \"read\", \"path\": \"contact.md\"}"), ScriptedRun.done("noted"));
        HarnessExecutor executor = run.executor(script);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        assertThat(run.seen()).doesNotContain("ann@example.com").doesNotContain("555-123-4567").contains("[EMAIL]");
    }

    @Test
    @Tag("V7.3")
    @Tag("H4")
    void aFileToolRootedAtTheScriptDirectoryCannotReadOrChangeTheRunsOwnJournal() throws Exception {
        Path journalFile = dir.resolve("runs/today/journal.json");
        Files.createDirectories(journalFile.getParent());
        String script = """
                tool Files { use: file  root: "."  mode: readwrite  overwrite: true  allow: "*.json, *.md" }
                agent Snoop { model: "m" tools: [Files]  max_iterations: 10 }
                workflow Main() { delegate "look around" to Snoop -> r }
                """;
        ScriptedRun run = new ScriptedRun(dir).replies(
                ScriptedRun.call("Files", "{\"action\": \"read\", \"path\": \"runs/today/journal.json\"}"),
                ScriptedRun.call("Files", "{\"action\": \"write\", \"path\": \"runs/today/journal.json\", \"content\": \"{}\"}"),
                ScriptedRun.call("Files", "{\"action\": \"list\", \"path\": \"runs/today\"}"),
                ScriptedRun.done("done"));
        run.journal = new FileRunJournal(journalFile);
        HarnessExecutor executor = run.executor(script);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        List<io.github.llm4j.model.Message> last = run.requests.get(run.requests.size() - 1).getMessages();
        String scratchpad = last.get(last.size() - 1).getContent();
        assertThat(scratchpad).contains("Observation: Error: refused: that location belongs to the run itself");
        assertThat(scratchpad.split("Observation:", -1).length - 1).isEqualTo(3);
        assertThat(Files.readString(journalFile)).as("the journal was not overwritten").isNotEqualTo("{}");
    }
}
