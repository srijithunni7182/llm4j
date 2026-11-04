package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 24: the edges of the extract step that the main tests do not reach (mutation testing). */
class ExtractRunnerEdgesTest {

    static final String SCRIPT =
            """
            extractor Person {
                model: "m"
                fields { name: string }
                pii: mask
            }
            extractor Plain { model: "m" fields { name: string } }
            workflow Retrying(text) {
                extract Plain from text -> r retry 3 backoff 2s on_failure { note "failed: {_error}" }
            }
            workflow Masking(text) {
                extract Person from text -> r
                note "name={r.name}"
            }
            workflow Unset() {
                alt (1 == 2) { set later = "never" }
                extract Plain from later -> r on_failure { note "failed: {_error}" }
            }
            workflow Timed(text) {
                extract Plain from text -> r timeout 5s
                note "name={r.name}"
            }
            """;

    record Outcome(
            List<io.github.llm4j.model.LLMRequest> requests,
            List<String> notes,
            List<TraceEvent> events,
            List<Duration> sleeps,
            RunJournal journal,
            Throwable error) {}

    static Outcome run(String workflow, Map<String, String> inputs, RunJournal journal, String... replies) {
        CapturingClient client = new CapturingClient(replies);
        LoomScript script = new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> client);
        List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
        executor.setSleeper(sleeps::add);
        if (journal != null) executor.setJournal(journal);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        executor.addTraceListener(events::add);
        executor.initialize();
        Throwable error = null;
        try {
            executor.executeWorkflow(workflow, inputs);
        } catch (Throwable t) {
            error = t;
        }
        List<String> notes =
                events.stream()
                        .filter(e -> e.type().equals(TraceEvent.NOTE))
                        .map(TraceEvent::text)
                        .toList();
        return new Outcome(client.requests(), notes, events, sleeps, executor.getJournal(), error);
    }

    @Test
    void retriesWaitTheBackoffDoublingEachTimeWithoutRealWaiting() {
        Outcome o = run("Retrying", Map.of("text", "x"), null, "not json");

        // 1 + 3 retries + 1 correction = 5 attempts, so 4 waits: 2s, 4s, 8s, 16s
        assertThat(o.sleeps())
                .containsExactly(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(16));
    }

    @Test
    void aFailureIsRecordedSoAResumedRunGoesStraightToOnFailureWithoutAskingAgain() {
        Outcome first = run("Retrying", Map.of("text", "x"), null, "not json");
        Outcome again = run("Retrying", Map.of("text", "x"), first.journal(), "{\"name\": \"A\"}");

        assertThat(again.requests()).isEmpty();
        assertThat(again.notes()).singleElement().asString().startsWith("failed:");
    }

    @Test
    void personalDataIsMaskedBeforeItLeavesAndNeverReachesTheModel() {
        Outcome o =
                run(
                        "Masking",
                        Map.of("text", "Write to jane.doe@example.com about the order"),
                        null,
                        "{\"name\": \"Jane\"}");

        String sent = CapturingClient.text(o.requests().get(0));
        assertThat(sent).doesNotContain("jane.doe@example.com").contains("about the order");
        assertThat(o.events().stream().anyMatch(e -> "pii_masked".equals(String.valueOf(e.data().get("event")))))
                .isTrue();
    }

    @Test
    void anInputThatHasNoValueFailsTheStepWithoutAskingTheModel() {
        Outcome o = run("Unset", Map.of(), null, "{\"name\": \"A\"}");

        assertThat(o.requests()).isEmpty();
        assertThat(o.notes()).singleElement().asString().contains("later");
    }

    @Test
    void aStepTimeoutDoesNotChangeASuccessfulAnswer() {
        Outcome o = run("Timed", Map.of("text", "x"), null, "{\"name\": \"A\"}");

        assertThat(o.error()).isNull();
        assertThat(o.notes()).contains("name=A");
    }

    @Test
    void aReplyThatIsJsonOverSeveralLinesIsReadAndAnUnreadableOneNamesTheFirstLineOnly() {
        Outcome ok = run("Timed", Map.of("text", "x"), null, "Sure!\n```json\n{\n \"name\": \"A\"\n}\n```");
        assertThat(ok.notes()).contains("name=A");

        Outcome bad = run("Retrying", Map.of("text", "x"), null, "{ \"name\": \n oops }");
        assertThat(bad.notes().get(0)).startsWith("failed:").doesNotContain("\n");
    }
}
