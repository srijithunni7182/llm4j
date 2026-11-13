package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.children.ChildHarness;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a signal leaves behind for a reviewer: the audit event and the journal entry at the step
 * (phase 34c, D13). Each is the record that lets {@code weave why} say what was sent, read or waited
 * for in vain, so each is asserted, not just run.
 */
class SignalAuditTrailTest {

    private final ChildHarness h = new ChildHarness();
    private final List<String> audits = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> auditData = Collections.synchronizedList(new ArrayList<>());

    private static final String WAITER =
            "workflow Child(x) { wait signal \"go\" timeout 10m -> text  alt (text is ok) { return text } else { return \"timed out\" } }\n";

    private HarnessExecutor run(String source) {
        ControlledChildScheduler scheduler = h.controlled();
        h.task(
                "LetItWait",
                ctx -> {
                    scheduler.runNext();
                    return TaskResult.value("ran");
                });
        HarnessExecutor e = h.parent(source, scheduler);
        e.setAuditLogger(
                new io.github.llm4j.audit.AuditLogger() {
                    @Override
                    public void logAgentDecision(io.github.llm4j.audit.AuditEvent event) {}

                    @Override
                    public void logToolExecution(String s, String t, String i, String o, Instant ts) {}

                    @Override
                    public void logPromptUsage(String s, String p, String v, Instant ts) {}

                    @Override
                    public void logConversationEvent(String s, String u, String type, Map<String, Object> data) {
                        audits.add(type);
                        auditData.add(data);
                    }
                });
        e.executeWorkflow("Main", Map.of("q", "x"));
        return e;
    }

    private List<RunJournal.Entry> parentEntries(String kind) {
        return h.journals.get("p").all().values().stream().filter(en -> kind.equals(en.kind())).toList();
    }

    @Test
    void aNotifyIsJournaledAtItsStepAndAuditedWithItsDirectionAndName() {
        run(
                WAITER
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "go" "hello"
                            wait work timeout 1m -> out
                        }
                        """);

        assertThat(audits).contains("signal_sent");
        assertThat(auditData).anyMatch(d -> "to child".equals(d.get("direction")) && "go".equals(d.get("name")));
        Map<?, ?> record = (Map<?, ?>) parentEntries("notify").get(0).value();
        assertThat(record.get("delivered")).isEqualTo(true);
        assertThat(record.containsKey("reason")).isFalse();
    }

    @Test
    void aNotifyToACancelledChildSaysItWasWrittenButWillNotBeRead() {
        run(
                WAITER
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick
                            cancel work reason "enough"
                            notify work "go" "too late"
                        }
                        """);

        Map<?, ?> record = (Map<?, ?>) parentEntries("notify").get(0).value();
        assertThat(record.get("delivered")).isEqualTo(false);
        assertThat(String.valueOf(record.get("reason"))).contains("already cancelled");
    }

    @Test
    void aWaitThatReadsASignalIsJournaledAndAuditedAsReceived() {
        run(
                WAITER.replace("-> text  alt (text is ok) { return text } else { return \"timed out\" }", "-> text  notify parent \"ping\" \"hi\"  return text")
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            notify work "go" "hello"
                            wait work signal "ping" timeout 1m -> got
                            return got
                        }
                        """);

        assertThat(audits).contains("signal_received");
        assertThat(auditData).anyMatch(d -> "ping".equals(d.get("name")) && Boolean.FALSE.equals(d.get("sticky")));
        assertThat(parentEntries("signal-wait")).isNotEmpty();
    }

    @Test
    void aWaitThatTimesOutIsAuditedAsTimedOutAndSaysSoInTheJournal() {
        run(
                WAITER
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work
                            run LetItWait() -> kick
                            wait work signal "never" timeout 1m -> got
                            set seen = work.status
                        }
                        """);

        assertThat(audits).contains("signal_timeout");
        assertThat(auditData).anyMatch(d -> "never".equals(d.get("name")) && d.size() == 1);
        assertThat(audits).doesNotContain("signal_received");
        assertThat(parentEntries("signal-wait")).hasSize(1);
    }

    @Test
    void aWaitAnyIsAuditedWithTheKindOfEventItReturned() {
        run(
                WAITER
                        + """
                        workflow Main(q) {
                            start Child(x = q) budget { tokens: 10 } -> work["a"]
                            notify work["a"] "go" "hello"
                            wait any work timeout 1m -> event
                            set seen = event.kind
                        }
                        """);

        assertThat(audits).contains("wait_any");
        assertThat(auditData).anyMatch(d -> "finished".equals(d.get("kind")) || "signal".equals(d.get("kind")));
        assertThat(parentEntries("wait-any")).hasSize(1);
    }
}
