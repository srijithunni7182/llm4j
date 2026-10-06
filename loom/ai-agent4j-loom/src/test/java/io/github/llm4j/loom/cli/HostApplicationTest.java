package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An application with a user interface hosts a workflow: the interface and the agents are the application's, and the workflow only has to
 * (1) say what it is doing, so a transcript can be shown, (2) ask the person through the application, and (3) hand back what it made.
 * This is the pattern chapter 9 of the guide shows, run against the real executor.
 */
class HostApplicationTest {

    private static final String SCRIPT = """
            agent Writer { model: "m" }
            workflow Main(topic) {
                delegate "Draft a post about {topic}" to Writer -> post_text
                human_prompt "Publish this? {post_text}" -> decision
                note "Decision was: {decision}"
            }
            """;

    private HarnessExecutor host(Path dir, HumanInterface person, List<TraceEvent> transcript) throws Exception {
        Path script = Files.writeString(dir.resolve("w.loom"), SCRIPT);
        HarnessExecutor executor = new HarnessExecutor(new LoomLoader().load(script.toString()), new ToolRegistry(), new MockModels());
        executor.setHumanInterface(person);
        executor.addTraceListener(transcript::add);   // before initialize()
        executor.setBaseDir(dir);
        return executor;
    }

    @Test
    void theApplicationSeesATranscriptAnswersTheQuestionAndReadsTheResult(@TempDir Path dir) throws Exception {
        List<TraceEvent> transcript = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        HarnessExecutor executor = host(dir, message -> { asked.add(message); return "yes"; }, transcript);
        try {
            executor.initialize();
            executor.executeWorkflow("Main", Map.of("topic", "composting"));
        } finally {
            executor.shutdown();
        }

        assertThat(transcript).extracting(TraceEvent::type).contains(TraceEvent.DELEGATE_START, TraceEvent.DELEGATE_END, TraceEvent.NOTE);
        assertThat(transcript.stream().filter(e -> e.type().equals(TraceEvent.DELEGATE_START)).findFirst().orElseThrow().agent()).isEqualTo("Writer");
        assertThat(transcript.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).findFirst().orElseThrow().text()).isEqualTo("Decision was: yes");
        assertThat(asked).singleElement().asString().startsWith("Publish this? [mock");
        assertThat(executor.getContext().getAll()).containsEntry("decision", "yes").containsKey("post_text");
    }

    @Test
    void theNoteIsInTheTranscriptInTheOrderTheWorkflowRan(@TempDir Path dir) throws Exception {
        List<TraceEvent> transcript = new ArrayList<>();
        HarnessExecutor executor = host(dir, m -> "no", transcript);
        try {
            executor.initialize();
            executor.executeWorkflow("Main", Map.of("topic", "x"));
        } finally {
            executor.shutdown();
        }
        List<String> order = transcript.stream().map(TraceEvent::type).filter(t -> t.equals(TraceEvent.DELEGATE_END) || t.equals(TraceEvent.NOTE)).toList();
        assertThat(order).containsExactly(TraceEvent.DELEGATE_END, TraceEvent.NOTE);
    }

    @Test
    void anApplicationThatCannotAnswerNowPausesTheRunWithoutHoldingAThread(@TempDir Path dir) throws Exception {
        List<TraceEvent> transcript = new ArrayList<>();
        List<String> stepIds = new ArrayList<>();
        HumanInterface later = new HumanInterface() {
            @Override public String promptHuman(String message) { throw new IllegalStateException("the step-aware form is used"); }
            @Override public String promptHuman(String stepId, String message) { stepIds.add(stepId); throw new RunSuspended(stepId, message); }
        };
        HarnessExecutor executor = host(dir, later, transcript);
        try {
            executor.initialize();
            assertThatThrownBy(() -> executor.executeWorkflow("Main", Map.of("topic", "x"))).isInstanceOf(RunSuspended.class);
        } finally {
            executor.shutdown();
        }
        assertThat(stepIds).hasSize(1).doesNotContain("");
        assertThat(transcript).extracting(TraceEvent::type).doesNotContain(TraceEvent.NOTE);
    }

    @Test
    void theAnswerArrivesLaterAndTheRunContinuesWithoutCallingTheModelAgain(@TempDir Path dir) throws Exception {
        io.github.llm4j.loom.runtime.RunJournal journal = io.github.llm4j.loom.runtime.RunJournal.inMemory();
        String[] paused = new String[1];
        HumanInterface later = new HumanInterface() {
            @Override public String promptHuman(String message) { throw new IllegalStateException(); }
            @Override public String promptHuman(String stepId, String message) { paused[0] = stepId; throw new RunSuspended(stepId, message); }
        };
        HarnessExecutor first = host(dir, later, new ArrayList<>());
        first.setJournal(journal);
        try {
            first.initialize();
            assertThatThrownBy(() -> first.executeWorkflow("Main", Map.of("topic", "x"))).isInstanceOf(RunSuspended.class);
        } finally {
            first.shutdown();
        }

        journal.answer(paused[0], "yes");
        List<TraceEvent> transcript = new ArrayList<>();
        HarnessExecutor second = host(dir, later, transcript);
        second.setJournal(journal);
        try {
            second.initialize();
            second.executeWorkflow("Main", Map.of("topic", "x"));
        } finally {
            second.shutdown();
        }
        assertThat(transcript).extracting(TraceEvent::type).contains(TraceEvent.DELEGATE_REPLAYED, TraceEvent.NOTE).doesNotContain(TraceEvent.DELEGATE_START);
        assertThat(second.getContext().getAll()).containsEntry("decision", "yes");
    }

    @Test
    void theGuideAndTheSkillTeachThePatternThisTestRuns() throws Exception {
        String chapter = Files.readString(Path.of("../../docs/guide/09-go-live.md"));
        assertThat(chapter).contains("## An application with its own interface").contains("executor.addTraceListener(").contains("executor.setHumanInterface(")
                .contains("executor.getContext().getAll()").contains("RunSuspended(stepId, message)").contains("journal.answer(stepId, answer)").contains("`note`");
        assertThat(Files.readString(Path.of("../../.claude/skills/llm4j-workflow-guide/SKILL.md"))).contains("addTraceListener").contains("HumanInterface").contains("An application with its own interface");
        for (String type : new String[] {"delegate_start", "delegate_end", "thought", "action", "observation", "note", "approval", "budget", "guard", "checkpoint", "rewind", "suspended"}) {
            assertThat(chapter).contains("`" + type + "`");
        }
    }
}
