package io.github.llm4j.loom.rewind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.runtime.RunJournal;
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

/** Where a rewind can sit and what it takes along: handlers, loops, people's answers, caps (spec loom-rewind-and-fork R2, R4, R6). */
class RewindShapesTest {

    @TempDir
    Path dir;

    private static final Pattern FEEDBACK = Pattern.compile("Feedback: (\\S+)");
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    /** The task a model is asked, without the conversation history in front of it. */
    static String task(io.github.llm4j.model.LLMRequest request) {
        String message = ScriptedRun.lastMessage(request);
        int marker = message.lastIndexOf("Current Task:");
        return marker < 0 ? message : message.substring(marker + "Current Task:".length()).trim();
    }

    static String feedback(String task) {
        Matcher m = FEEDBACK.matcher(task);
        return m.find() ? m.group(1) : "none";
    }

    /** Writer answers "draft(<feedback>)"; the reviewer gives 9 once the draft shows feedback, 3 before; "Flaky" fails until it has feedback. */
    String model(io.github.llm4j.model.LLMRequest request) {
        String system = request.getMessages().get(0).getContent();
        String task = task(request);
        String who = system.replace("You are ", "").replace(".", "").trim();
        calls.add(who + ": " + task);
        return switch (who) {
            case "Writer" -> ScriptedRun.done("draft(" + feedback(task) + ")");
            case "Reviewer" -> "```json\n{\"score\": " + (task.contains("draft(fix") ? 9 : 3) + ", \"notes\": \"fix1\"}\n```";
            case "Flaky" -> {
                if (task.contains("{hint}")) throw new IllegalStateException("the service is down"); // the first attempt: nothing carried in yet
                yield ScriptedRun.done("price list");
            }
            default -> ScriptedRun.done("ok");
        };
    }

    ScriptedRun run(RunJournal journal) {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = this::model;
        run.journal = journal;
        return run;
    }

    HarnessExecutor start(ScriptedRun run, String script) {
        HarnessExecutor executor = run.executor(script);
        executor.initialize();
        return executor;
    }

    static final String AGENTS = """
            agent Writer   { model: "m" system: "You are Writer." }
            agent Reviewer { model: "m" system: "You are Reviewer." }
            agent Flaky    { model: "m" system: "You are Flaky." max_iterations: 2 }
            """;

    private long calls(String who) {
        return calls.stream().filter(c -> c.startsWith(who + ":")).count();
    }

    @Test
    @Tag("RW-V2.7")
    void aFailedStepCanSendTheRunBackOnceAndTheSecondAttemptSucceeds() {
        ScriptedRun run = run(RunJournal.inMemory());
        start(run, AGENTS + """
                workflow W() {
                    delegate "Fetch. Feedback: {hint}" to Flaky -> prices
                        on_failure { rewind to start at most 1 time carrying hint = "{_error}" }
                    note "got {prices}"
                }
                """).executeWorkflow("W", Map.of());

        assertThat(calls("Flaky")).isEqualTo(2);
        assertThat(calls.get(calls.size() - 1)).contains("Feedback: the service is down");
        assertThat(run.audit).contains("run_rewound");
    }

    @Test
    @Tag("RW-V2.11")
    void aRewindToACheckpointOutsideALoopStartsTheLoopAgainFromRoundOne() {
        ScriptedRun run = run(RunJournal.inMemory());
        RunJournal journal = run.journal;
        start(run, AGENTS + """
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    loop until (w == "draft(fix1)") max 3 {
                        delegate "Write. Feedback: {feedback}" to Writer -> w
                        rewind to a when (w == "draft(none)") at most 1 time carrying feedback = "fix1"
                    }
                    note "done {w}"
                }
                """).executeWorkflow("W", Map.of());

        assertThat(calls("Writer")).isEqualTo(2);
        assertThat(journal.all().keySet()).anyMatch(k -> k.startsWith("W/s1/r1.0")).anyMatch(k -> k.startsWith("W/s1~2/r1.0"));
    }

    @Test
    @Tag("RW-V2.6")
    void aRewindInsideABranchGoesBackToACheckpointAroundIt() {
        ScriptedRun run = run(RunJournal.inMemory());
        start(run, AGENTS + """
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback}" to Writer -> w
                    alt (w == "draft(none)") {
                        rewind to a at most 1 time carrying feedback = "fix1"
                    }
                    note "done {w}"
                }
                """).executeWorkflow("W", Map.of());

        assertThat(calls("Writer")).isEqualTo(2);
    }

    @Test
    @Tag("RW-V2.4")
    void aCapOnAllRewindsStopsARunThatKeepsGoingBackAndNamesTheBusiestOnes() {
        ScriptedRun run = run(RunJournal.inMemory());
        HarnessExecutor executor = start(run, AGENTS + """
                workflow W() {
                    checkpoint a
                    delegate "Write" to Writer -> w
                    rewind to a at most 5 times
                }
                """);
        executor.setMaxRewinds(3);
        assertThatThrownBy(() -> executor.executeWorkflow("W", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("most it may (3)").hasMessageContaining("W/s2");
    }

    @Test
    @Tag("RW-V2.2")
    void theCountReasonAndTargetOfARewindAreVariablesInTheNewAttempt() {
        ScriptedRun run = run(RunJournal.inMemory());
        start(run, AGENTS + """
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback} Count: {_rewind}/{_rewindTo}/{_rewindReason}" to Writer -> w
                    rewind to a when (w == "draft(none)") at most 1 time carrying feedback = "fix1"
                }
                """).executeWorkflow("W", Map.of());

        assertThat(calls.stream().filter(c -> c.startsWith("Writer")).toList().get(1)).contains("Count: 1/a/w==draft(none)");
    }

    @Test
    @Tag("RW-V4.6")
    void anIdenticalQuestionIsNotAskedAgainAfterARewindAndAChangedOneIs() {
        String body = """
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    human_prompt "%s" -> ok
                    delegate "Write. Feedback: {feedback}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}"
                }
                """;
        ScriptedRun same = run(RunJournal.inMemory());
        start(same, AGENTS + body.formatted("Ready to go?")).executeWorkflow("W", Map.of());
        assertThat(same.questions).hasSize(1);

        calls.clear();
        ScriptedRun changed = run(RunJournal.inMemory());
        start(changed, AGENTS + body.formatted("Ready to go? ({feedback})")).executeWorkflow("W", Map.of());
        assertThat(changed.questions).hasSize(2);
        assertThat(changed.questions.get(1)).contains("fix1");
    }

    @Test
    @Tag("RW-V4.7")
    void anApprovedCallWithTheSameArgumentsIsNotAskedAgainAfterARewind() {
        ScriptedRun run = new ScriptedRun(dir);
        run.responder = request -> {
            String system = request.getMessages().get(0).getContent();
            String message = ScriptedRun.lastMessage(request);
            String task = task(request);
            if (system.contains("Reviewer")) return "```json\n{\"score\": " + (task.contains("sent(fix") ? 9 : 3) + ", \"notes\": \"fix1\"}\n```";
            if (message.lastIndexOf("Observation:") > message.lastIndexOf("Current Task:")) return ScriptedRun.done("sent(" + feedback(task) + ")");
            return ScriptedRun.call("Log", "{\"action\": \"append\", \"path\": \"n.md\", \"content\": \"hello\"}");
        };
        run.journal = RunJournal.inMemory();
        start(run, """
                tool Log { use: file  root: "out"  mode: write }
                agent Sender { model: "m" system: "You are Sender." tools: [Log] approve: [Log] max_iterations: 6 }
                agent Reviewer { model: "m" system: "You are Reviewer." }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Send. Feedback: {feedback}" to Sender -> sent
                    delegate "Review {sent}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}" side effects: keep
                }
                """).executeWorkflow("W", Map.of());

        assertThat(run.questions.stream().filter(q -> q.contains("wants to call Log")).count()).isEqualTo(1);
        assertThat(run.audit).contains("run_rewound");
    }

    @Test
    @Tag("RW-V6.1")
    void tokensSpentByADiscardedAttemptStayInTheJournalAndInTheSpendReport() {
        ScriptedRun run = run(RunJournal.inMemory());
        HarnessExecutor executor = start(run, AGENTS + """
                budget { tokens: 100000 }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}"
                }
                """);
        executor.executeWorkflow("W", Map.of());

        var usage = run.journal.all().keySet().stream().filter(k -> k.contains("#usage:")).toList();
        assertThat(usage).anyMatch(k -> k.startsWith("W/s1#usage:")).anyMatch(k -> k.startsWith("W/s1~2#usage:"));
        assertThat(usage).hasSize(4); // writer and reviewer, in both attempts
    }

    @Test
    @Tag("RW-V6.2")
    void aRunThatHasNothingLeftToSpendDoesNotGoBack() {
        ScriptedRun run = run(RunJournal.inMemory());
        // three model calls are all the run may make: the first attempt uses them, so a rewind would only spend what isn't there
        HarnessExecutor executor = start(run, AGENTS + """
                budget { calls: 3 }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    delegate "Check {draft}" to Writer -> check
                    rewind to a when (review.score < 7) at most 2 times carrying feedback = "{review.notes}"
                        if it still fails { note "out of budget, not going back" }
                }
                """);
        executor.executeWorkflow("W", Map.of());

        assertThat(calls("Writer")).isEqualTo(2); // write and check of the one attempt
        assertThat(run.audit).contains("rewind_exhausted").doesNotContain("run_rewound");
    }

    @Test
    @Tag("RW-V6.1")
    void theSecondAttemptSpendsWhatTheFirstLeftNotAFreshBudget() {
        ScriptedRun run = run(RunJournal.inMemory());
        HarnessExecutor executor = start(run, AGENTS + """
                budget { calls: 5 }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    delegate "Check {draft}" to Writer -> check
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}"
                }
                """);
        assertThatThrownBy(() -> executor.executeWorkflow("W", Map.of())).isInstanceOf(io.github.llm4j.budget.BudgetExceeded.class);
        assertThat(calls.size()).as("the first attempt's three calls count: only two were left for the second").isEqualTo(5);
    }

    @Test
    @Tag("RW-V2.4")
    void withoutAnyCapOnTheCommandLineARunStillStopsAfterTwentyRewinds() {
        ScriptedRun run = run(RunJournal.inMemory());
        HarnessExecutor executor = start(run, AGENTS + """
                workflow W() {
                    checkpoint a
                    delegate "Write" to Writer -> w
                    rewind to a at most 50 times
                }
                """);
        assertThatThrownBy(() -> executor.executeWorkflow("W", Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("most it may (20)");
    }

    @Test
    @Tag("RW-V6.1")
    void aResumedRunCountsTheSpendOfReplacedAttemptsToo() {
        RunJournal journal = RunJournal.inMemory();
        String script = AGENTS + """
                budget { tokens: 100000 }
                workflow W() {
                    checkpoint a  starting with feedback = "none"
                    delegate "Write. Feedback: {feedback}" to Writer -> draft
                    delegate "Review {draft}" to Reviewer -> review expecting { score: number, notes: string }
                    rewind to a when (review.score < 7) at most 1 time carrying feedback = "{review.notes}"
                }
                """;
        start(run(journal), script).executeWorkflow("W", Map.of());

        HarnessExecutor resumed = start(run(journal), script);
        resumed.executeWorkflow("W", Map.of());

        assertThat(resumed.spend().total().calls()).isEqualTo(4); // writer and reviewer, in both attempts
    }
}
