package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.RunTravel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resuming after a rewind or a crash, old journals, and what is spent (spec loom-rewind-and-fork R2.10, R3, R4.11, R8.5, R9). */
class RewindResumeTest {

    @TempDir
    Path dir;

    private ScriptedRun run(ReportScript model, RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = model::reply;
        run.journal = journal;
        return run;
    }

    @Test
    @Tag("RW-V2.10")
    void aResumedRunTakesTheDecisionItTookNotANewOneFromChangedData() {
        ReportScript model = new ReportScript(9); // the review passes, so no rewind
        RunJournal journal = RunJournal.inMemory();
        var first = run(model, journal).executor(ReportScript.SCRIPT);
        first.initialize();
        first.executeWorkflow("Report", Map.of("topic", "bees"));
        assertThat(journal.get("Report/s5#rewind")).isPresent();
        assertThat(journal.all()).doesNotContainKey("#boundaries");

        // the recorded review changes under the run's feet (a hand-edited journal): the condition would now be true
        journal.put("Report/s4", new RunJournal.Entry("delegate", Map.of("score", 1, "notes", "fix1")));
        ReportScript again = new ReportScript(9);
        var second = run(again, journal).executor(ReportScript.SCRIPT);
        second.initialize();
        second.executeWorkflow("Report", Map.of("topic", "bees"));

        assertThat(journal.all()).doesNotContainKey("#boundaries");
        assertThat(again.calls).as("nothing ran again").isEmpty();
    }

    @Test
    @Tag("RW-V3.5")
    void theNextAttemptSeesTheSameValuesWhetherTheRewindWasLiveOrFoundOnResume() {
        ReportScript live = new ReportScript(5, 8);
        run(live, RunJournal.inMemory());
        var a = run(live, RunJournal.inMemory()).executor(ReportScript.SCRIPT);
        a.initialize();
        a.executeWorkflow("Report", Map.of("topic", "bees"));
        List<String> liveTasks = new ArrayList<>(live.tasks);

        // a crash right after the rewind was decided, then a resume
        ReportScript crashing = new ReportScript(5, 8);
        RunJournal durable = RunJournal.inMemory();
        int boundaryWrite = 0;
        FaultRunJournal count = new FaultRunJournal(RunJournal.inMemory(), 0, false);
        var c = run(new ReportScript(5, 8), count).executor(ReportScript.SCRIPT);
        c.initialize();
        c.executeWorkflow("Report", Map.of("topic", "bees"));
        for (var e : count.all().keySet()) if (e.equals("#boundaries")) boundaryWrite = 1;
        assertThat(boundaryWrite).isEqualTo(1);
        int writes = count.puts();
        for (int crashAt = 1; crashAt <= writes; crashAt++) {
            RunJournal j = RunJournal.inMemory();
            ReportScript m = new ReportScript(5, 8);
            try {
                var e1 = run(m, new FaultRunJournal(j, crashAt, true)).executor(ReportScript.SCRIPT);
                e1.initialize();
                e1.executeWorkflow("Report", Map.of("topic", "bees"));
            } catch (FaultRunJournal.Crash crash) {
                // resumed below
            }
            var e2 = run(m, j).executor(ReportScript.SCRIPT);
            e2.initialize();
            e2.executeWorkflow("Report", Map.of("topic", "bees"));
            assertThat(m.tasks.stream().filter(t -> t.startsWith("Collector")).reduce((x, y) -> y).orElse("")).as("crash after write " + crashAt)
                    .isEqualTo(liveTasks.stream().filter(t -> t.startsWith("Collector")).reduce((x, y) -> y).orElse(""));
        }
        assertThat(crashing.calls).isEmpty();
        assertThat(durable.all()).isEmpty();
    }

    @Test
    @Tag("RW-V3.6")
    @Tag("RW-V6.3")
    void spentTokensAreCountedInEveryAttemptAndTheTimelineSplitsKeptFromReplaced() {
        ReportScript model = new ReportScript(5, 8);
        RunJournal journal = RunJournal.inMemory();
        var e = run(model, journal).executor("budget { tokens: 1000000 }\n" + ReportScript.SCRIPT);
        e.initialize();
        e.executeWorkflow("Report", Map.of("topic", "bees"));

        long all = journal.all().entrySet().stream().filter(x -> x.getKey().contains("#usage:"))
                .mapToLong(x -> ((Number) ((Map<?, ?>) x.getValue().value()).get("prompt")).longValue() + ((Number) ((Map<?, ?>) x.getValue().value()).get("completion")).longValue()).sum();
        var timeline = RunTravel.timeline(journal);
        assertThat(timeline.keptTokens() + timeline.discardedTokens()).isEqualTo(all);
        assertThat(timeline.discardedTokens()).isGreaterThan(0);
        assertThat(timeline.keptTokens()).isGreaterThan(0);
        assertThat(e.spend().table()).contains("total");
        assertThat(e.spend().lines().stream().mapToLong(l -> l.charge().promptTokens() + l.charge().completionTokens()).sum()).as("the spend report counts the replaced attempt too").isEqualTo(all);
    }

    @Test
    @Tag("RW-V3.8")
    void aJournalWrittenBeforeThisFeatureResumesWithTheSameResultsAndAddsNothingNew() throws IOException {
        Path journalFile = dir.resolve("legacy.json");
        try (Stream<String> ignored = Stream.of("x")) {
            Files.copy(Path.of("src/test/resources/legacy/paused-journal.json"), journalFile, StandardCopyOption.REPLACE_EXISTING);
        }
        FileRunJournal journal = new FileRunJournal(journalFile);
        List<String> tasks = Collections.synchronizedList(new ArrayList<>());
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = journal;
        run.responder = r -> {
            tasks.add(ScriptedRun.lastMessage(r));
            return ScriptedRun.done("finished");
        };
        var executor = run.executor("""
                agent A { model: "m" system: "You are A." }
                workflow W() {
                    delegate "first" to A -> prev
                    human_prompt "Go on?" -> go
                    delegate "second, after {prev} and {go}" to A -> next
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0)).contains("second, after legacy-result and yes");
        assertThat(journal.all().keySet()).noneMatch(k -> k.contains("#boundaries") || k.contains("~") || k.contains("#asked") || k.contains("#checkpoint"));
        assertThat(journal.get("W/s0").get().value()).isEqualTo("legacy-result");
        assertThat(journal.get("W/s2").get().value()).isEqualTo("finished");
    }

    @Test
    @Tag("RW-V8.5")
    void aToolOfAnUnknownClassHoldsARewindUntilAPersonSaysSo() {
        java.util.concurrent.atomic.AtomicInteger stamps = new java.util.concurrent.atomic.AtomicInteger();
        ScriptedRun run = new ScriptedRun(dir);
        run.journal = RunJournal.inMemory();
        run.tools.register("Stamp", new io.github.llm4j.agent.Tool() {
            @Override public String getName() { return "Stamp"; }
            @Override public String getDescription() { return "stamps"; }
            @Override public String execute(Map<String, Object> args) { stamps.incrementAndGet(); return "stamped"; }
        });
        run.human = q -> "cancel";
        run.responder = r -> {
            String system = r.getMessages().get(0).getContent();
            String message = ScriptedRun.lastMessage(r);
            if (system.contains("Reviewer")) return "```json\n{\"score\": 1, \"notes\": \"x\"}\n```";
            if (message.contains("Observation:")) return ScriptedRun.done("stamped it");
            return ScriptedRun.call("Stamp", "{\"doc\": \"a\"}");
        };
        var executor = run.executor("""
                agent Stamper { model: "m" system: "You are Stamper." tools: [Stamp] max_iterations: 4 }
                agent Reviewer { model: "m" system: "You are Reviewer." }
                workflow W() {
                    checkpoint a
                    delegate "Stamp it" to Stamper -> s
                    delegate "Review" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time
                }
                """);
        executor.initialize();
        executor.executeWorkflow("W", Map.of());

        assertThat(run.questions).hasSize(1);
        assertThat(run.questions.get(0)).contains("Stamp").contains("isn't known to change nothing");
        assertThat(stamps.get()).as("the person said cancel: no second attempt, no second stamp").isEqualTo(1);
    }
}
