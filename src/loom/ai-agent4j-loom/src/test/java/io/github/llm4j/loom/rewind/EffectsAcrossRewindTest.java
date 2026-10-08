package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a rewind does about things that already happened outside the run (spec loom-rewind-and-fork R4). */
class EffectsAcrossRewindTest {

    @TempDir
    Path dir;

    static final String SCRIPT = """
            tool Log { use: file  root: "out"  mode: write }
            agent Sender   { model: "m" system: "You are Sender."   tools: [Log] max_iterations: 6 }
            agent Reviewer { model: "m" system: "You are Reviewer." }
            workflow W() {
                checkpoint a  starting with feedback = "none"
                delegate "Send the note. Feedback: {feedback}" to Sender -> sent
                delegate "Review {sent}" to Reviewer -> review expecting { score: number, notes: string }
                rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}" %s
                note "finished {sent}"
            }
            """;

    private static final Pattern FEEDBACK = Pattern.compile("Feedback: (\\S+)");
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** The sender writes one line (its text is the feedback it was given if {@code vary}) and says what it wrote. */
    String model(io.github.llm4j.model.LLMRequest request, boolean vary) {
        String system = request.getMessages().get(0).getContent();
        String message = ScriptedRun.lastMessage(request);
        int marker = message.lastIndexOf("Current Task:");
        String task = marker < 0 ? message : message.substring(marker + "Current Task:".length()).trim();
        if (system.contains("Reviewer")) {
            calls.add("Reviewer");
            boolean second = task.contains("fix1");
            return "```json\n{\"score\": " + (second ? 9 : 3) + ", \"notes\": \"fix1\"}\n```";
        }
        calls.add("Sender");
        Matcher m = FEEDBACK.matcher(task);
        String feedback = m.find() ? m.group(1) : "none";
        if (message.contains("Observation:") && message.lastIndexOf("Observation:") > marker) return ScriptedRun.done("sent[" + feedback + "]");
        String text = vary ? "line-" + feedback : "same-line";
        return ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"notes.md\", \"content\": \"" + text + "\"}");
    }

    ScriptedRun run(RunJournal journal, boolean vary) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = r -> model(r, vary);
        run.journal = journal;
        return run;
    }

    HarnessExecutor start(ScriptedRun run, String policy) {
        HarnessExecutor executor = run.executor(SCRIPT.formatted(policy));
        executor.initialize();
        return executor;
    }

    long lines() throws IOException {
        Path f = dir.resolve("out/notes.md");
        return Files.exists(f) ? Files.readAllLines(f).stream().filter(l -> !l.isBlank()).count() : 0;
    }

    @Test
    @Tag("RW-V4.3")
    void askFirstHoldsARewindThatWouldCrossAnEffectAndRunsTheBlockedHandler() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), false);
        start(run, "if blocked { note \"held back\" }").executeWorkflow("W", Map.of());

        assertThat(calls.stream().filter("Sender"::equals)).hasSize(2); // the model call and the tool-using turn of one attempt
        assertThat(lines()).isEqualTo(1);
        assertThat(run.journal.all()).doesNotContainKey("#boundaries");
        assertThat(run.audit).contains("rewind_blocked").doesNotContain("run_rewound");
    }

    @Test
    @Tag("RW-V4.4")
    void withNoHandlerThePersonIsAskedAndKeepGoesBackWithoutSendingTheSameLineAgain() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), false);
        run.human = q -> "keep";
        start(run, "").executeWorkflow("W", Map.of());

        assertThat(run.questions).hasSize(1);
        assertThat(run.questions.get(0)).contains("side effects").contains("Log").contains("keep").contains("repeat").contains("cancel");
        assertThat(run.audit).contains("run_rewound");
        assertThat(calls.stream().filter("Reviewer"::equals)).hasSize(2); // the second attempt really ran
        assertThat(lines()).isEqualTo(1); // ... and the identical line was not written again
    }

    @Test
    @Tag("RW-V4.5")
    void repeatWritesTheSameLineAgain() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), false);
        run.human = q -> "repeat";
        start(run, "").executeWorkflow("W", Map.of());

        assertThat(lines()).isEqualTo(2);
    }

    @Test
    @Tag("RW-V4.4")
    void cancelDropsTheRewindAndTheRunGoesOn() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), false);
        run.human = q -> "cancel";
        start(run, "").executeWorkflow("W", Map.of());

        assertThat(lines()).isEqualTo(1);
        assertThat(calls.stream().filter("Reviewer"::equals)).hasSize(1);
        assertThat(run.audit).doesNotContain("run_rewound");
    }

    @Test
    @Tag("RW-V4.2")
    void keepRunsADifferentEffectAndSaysSo() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), true);
        start(run, "side effects: keep").executeWorkflow("W", Map.of());

        assertThat(lines()).isEqualTo(2);
        assertThat(run.trace.stream().map(t -> t.text()).toList()).anyMatch(t -> t.startsWith("a different effect after a rewind: Log"));
        assertThat(run.audit).contains("effect_after_rewind");
    }

    @Test
    @Tag("RW-V4.1")
    void anIdenticalEffectIsFoundAgainWhileTheModelCallIsMadeAgain() throws IOException {
        ScriptedRun run = run(RunJournal.inMemory(), false);
        start(run, "side effects: keep").executeWorkflow("W", Map.of());

        assertThat(lines()).isEqualTo(1);
        assertThat(calls.stream().filter("Sender"::equals).count()).isGreaterThanOrEqualTo(3); // each attempt's tool turn and answer
        assertThat(run.trace.stream().map(t -> t.text()).toList()).anyMatch(t -> t.contains("replayed") || t.contains("already"));
        assertThat(run.journal.all().keySet().stream().filter(k -> k.contains("#effect:")).toList()).hasSize(1);
    }

    @Test
    @Tag("RW-V4.4")
    void thePersonsAnswerIsInTheJournalSoAResumeDoesNotAskAgain() {
        RunJournal journal = RunJournal.inMemory();
        ScriptedRun first = run(journal, false);
        first.human = q -> "keep";
        start(first, "").executeWorkflow("W", Map.of());
        assertThat(first.questions).hasSize(1);

        ScriptedRun again = run(journal, false);
        again.human = q -> "should not be asked";
        start(again, "").executeWorkflow("W", Map.of());
        assertThat(again.questions).isEmpty();
    }
}
