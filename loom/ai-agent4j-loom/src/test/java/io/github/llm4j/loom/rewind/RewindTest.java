package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Declared rewinds (spec loom-rewind-and-fork, requirements R1 to R3). */
class RewindTest {

    @TempDir
    Path dir;

    private HarnessExecutor start(ScriptedRun run, String script) {
        HarnessExecutor executor = run.executor(script);
        executor.initialize();
        return executor;
    }

    private ScriptedRun run(ReportScript model, RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = model::reply;
        run.journal = journal;
        return run;
    }

    @Test
    @Tag("RW-V2.1")
    void aReviewThatFailsTwiceSendsTheRunBackTwiceAndTheThirdDraftIsPublished() {
        ReportScript model = new ReportScript(5, 6, 8);
        ScriptedRun run = run(model, RunJournal.inMemory());
        start(run, ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(model.count("Collector")).isEqualTo(3);
        assertThat(model.count("Writer")).isEqualTo(3);
        assertThat(model.count("Reviewer")).isEqualTo(3);
        assertThat(model.count("Publisher")).isEqualTo(1);
        assertThat(model.calls).endsWith("Reviewer", "Publisher");
    }

    @Test
    @Tag("RW-V2.3")
    void whenEveryAttemptFailsTheHandlerRunsAndTheRunCarriesOn() {
        ReportScript model = new ReportScript(5, 5, 5);
        ScriptedRun run = run(model, RunJournal.inMemory());
        start(run, ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(model.count("Reviewer")).isEqualTo(3); // the first, plus two rewinds
        assertThat(model.count("Publisher")).isEqualTo(1); // "if it still fails" ran, then the workflow went on
    }

    @Test
    @Tag("RW-V2.2")
    void whatTheReviewerSaidIsCarriedIntoTheNextAttempt() {
        ReportScript model = new ReportScript(5, 8);
        ScriptedRun run = run(model, RunJournal.inMemory());
        start(run, ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(model.tasks.stream().filter(t -> t.startsWith("Collector")).toList())
                .hasSize(2)
                .satisfies(t -> {
                    assertThat(t.get(0)).contains("Feedback so far: none");
                    assertThat(t.get(1)).contains("Feedback so far: fix1");
                });
    }

    @Test
    @Tag("RW-V3.1")
    @Tag("RW-V3.2")
    void aRewindOnlyAddsToTheJournalAndTheNewAttemptHasItsOwnIds() {
        ReportScript model = new ReportScript(5, 8);
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = run(model, journal);
        start(run, ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

        Map<String, RunJournal.Entry> all = journal.all();
        // generation 1 steps are still there, generation 2 has the same statements with a suffix
        assertThat(all).containsKeys("Report/s1", "Report/s2", "Report/s3", "Report/s4");
        assertThat(all).containsKeys("Report/s1~2", "Report/s2~2", "Report/s3~2", "Report/s4~2");
        assertThat(all.get("Report/s1").value()).isEqualTo("data(none)");
        assertThat(all.get("Report/s1~2").value()).isEqualTo("data(fix1)");
        assertThat(all).containsKey("#boundaries");
    }

    @Test
    @Tag("RW-V2.5")
    void variablesFromTheDiscardedAttemptAreGoneButThoseFromBeforeTheCheckpointRemain() {
        ReportScript model = new ReportScript(5, 8);
        ScriptedRun run = run(model, RunJournal.inMemory());
        HarnessExecutor executor = start(run, """
                agent Collector { model: "m" system: "You are Collector." }
                agent Reviewer { model: "m" system: "You are Reviewer." }
                agent Writer { model: "m" system: "You are Writer." }
                workflow W() {
                    delegate "Setup" to Writer -> setup
                    checkpoint a  starting with feedback = "none"
                    delegate "Collect. Feedback so far: {feedback}. Earlier draft: {draft}. Setup: {setup}" to Collector -> data
                    delegate "Write the report from {data}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}"
                }
                """);
        executor.executeWorkflow("W", Map.of());

        var collections = model.tasks.stream().filter(t -> t.startsWith("Collector")).toList();
        assertThat(collections).hasSize(2);
        assertThat(collections.get(1)).contains("Feedback so far: fix1").contains("Setup: draft from").doesNotContain("Earlier draft: draft from");
    }

    @Test
    @Tag("RW-V2.3")
    void withoutAHandlerAnExhaustedRewindFailsTheRunNamingTheStatement() {
        ReportScript model = new ReportScript(1);
        ScriptedRun run = run(model, RunJournal.inMemory());
        String script = ReportScript.SCRIPT.replace("if it still fails { note \"gave up\" }", "");
        HarnessExecutor executor = start(run, script);
        assertThatThrownBy(() -> executor.executeWorkflow("Report", Map.of("topic", "bees")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("still fails").hasMessageContaining("2 time");
    }

    /** A way to make a fresh durable journal; the crash test runs once for each. */
    private interface Journals {
        RunJournal fresh(int n) throws Exception;
    }

    private Journals memory() {
        return n -> RunJournal.inMemory();
    }

    private Journals file() {
        return n -> new io.github.llm4j.loom.runtime.FileRunJournal(dir.resolve("journal-" + n + ".json"));
    }

    private Journals jdbc() {
        return n -> {
            org.h2.jdbcx.JdbcDataSource db = new org.h2.jdbcx.JdbcDataSource();
            db.setURL("jdbc:h2:mem:rewind" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            io.github.llm4j.loom.runtime.JdbcRunJournal.createTable(db);
            return new io.github.llm4j.loom.runtime.JdbcRunJournal(db, "run-" + n);
        };
    }

    @Test
    @Tag("RW-V3.4")
    void aCrashAtAnyWriteDuringARewindResumesToTheSameStateOnEveryJournal() throws Exception {
        for (Journals journals : new Journals[] {memory(), file(), jdbc()}) crashAtEveryWrite(journals);
    }

    private void crashAtEveryWrite(Journals journals) throws Exception {
        // The uninterrupted run is the reference: its journal and the number of model calls it needed.
        ReportScript reference = new ReportScript(5, 6, 8);
        FaultRunJournal counting = new FaultRunJournal(journals.fresh(0), 0, false);
        start(run(reference, counting), ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));
        Map<String, Object> expected = values(counting.all());
        int writes = counting.puts();
        assertThat(writes).isGreaterThan(10);

        for (boolean afterWrite : new boolean[] {false, true}) {
            for (int crashAt = 1; crashAt <= writes; crashAt++) {
                RunJournal durable = journals.fresh(crashAt * 2 + (afterWrite ? 1 : 0));
                ReportScript model = new ReportScript(5, 6, 8);
                try {
                    start(run(model, new FaultRunJournal(durable, crashAt, afterWrite)), ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));
                } catch (FaultRunJournal.Crash expectedCrash) {
                    // the process "died" here; a new one resumes on whatever reached the journal
                }
                start(run(model, durable), ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

                String where = durable.getClass().getSimpleName() + ", crash " + (afterWrite ? "after" : "instead of") + " write " + crashAt;
                assertThat(values(durable.all())).as(where).isEqualTo(expected);
                // only the call that was in flight when the process died may be made twice
                assertThat(model.calls.size()).as(where + ": model calls").isLessThanOrEqualTo(reference.calls.size() + 1);
            }
        }
    }

    /** The journal's values by key, without the clock-dependent entries. */
    private static Map<String, Object> values(Map<String, RunJournal.Entry> journal) {
        Map<String, Object> out = new LinkedHashMap<>();
        journal.forEach((k, e) -> {
            if (k.contains("#usage") || k.endsWith("#checkpoint") || k.equals("#boundaries")) return;
            out.put(k, e.value());
        });
        return out;
    }

    @Test
    @Tag("RW-V2.8")
    void aConditionThatIsFalseMakesNoRewindAndLeavesNoBoundary() {
        ReportScript model = new ReportScript(9);
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun run = run(model, journal);
        start(run, ReportScript.SCRIPT).executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(model.count("Collector")).isEqualTo(1);
        assertThat(journal.all()).doesNotContainKey("#boundaries");
        assertThat(journal.all().keySet()).noneMatch(k -> k.contains("~"));
    }
}
