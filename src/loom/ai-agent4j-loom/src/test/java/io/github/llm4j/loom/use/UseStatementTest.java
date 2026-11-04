package io.github.llm4j.loom.use;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.Task;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.agent.task.TaskResult;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Phase 27b: {@code use Tool(...)} and {@code approve} on a step. */
class UseStatementTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    /** A tool that records what it was called with. */
    static final class Notify implements Tool {
        final List<Map<String, Object>> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failTimes = new AtomicInteger();
        String reply = "sent";

        @Override
        public String getName() {
            return "Notify";
        }

        @Override
        public String getDescription() {
            return "posts a note";
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return ToolSchema.object().string("text", "the note", true).string("channel", "where", false).build();
        }

        @Override
        public String execute(Map<String, Object> args) {
            if (failTimes.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IllegalStateException("channel down");
            calls.add(args);
            return reply;
        }
    }

    record Run(Throwable error, Map<String, Object> vars, List<TraceEvent> events, RunJournal journal, List<String> asked) {
        List<String> notes() {
            return events.stream().filter(e -> e.type().equals(TraceEvent.NOTE)).map(TraceEvent::text).toList();
        }
    }

    Run run(String source, Notify notify, String answer, RunJournal journal) {
        LoomScript script = parse(source);
        ToolRegistry tools = new ToolRegistry();
        tools.register("Notify", notify);
        HarnessExecutor ex = new HarnessExecutor(script, tools, m -> { throw new IllegalStateException("no model"); });
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        ex.setHumanInterface(
                new HumanInterface() {
                    @Override
                    public String promptHuman(String message) {
                        asked.add(message);
                        return answer;
                    }
                });
        TaskRegistry tasks = new TaskRegistry().register(Task.changes("Pay", io.github.llm4j.agent.tool.EffectPolicy.DEFAULT, c -> TaskResult.value("paid")));
        ex.setTaskRegistry(tasks);
        if (journal != null) ex.setJournal(journal);
        List<TraceEvent> events = Collections.synchronizedList(new ArrayList<>());
        ex.addTraceListener(events::add);
        ex.initialize();
        Throwable error = null;
        try {
            ex.executeWorkflow("Main", Map.of("draft", "Launch is Friday"));
        } catch (Throwable t) {
            error = t;
        }
        return new Run(error, ex.getContext().getAll(), events, ex.getJournal(), asked);
    }

    static final String SUBSTITUTION = "substitution: braces\n";

    // ---- R1: the statement

    @Test
    void parsesTheFullForm() {
        LoomScript s = parse(SUBSTITUTION + "workflow Main(draft) {\n  use Notify(text = draft, channel = \"team\") approve \"Post it?\" -> receipt retry 1 backoff 2s timeout 30s on_declined { note \"no\" } on_failure { note \"failed\" }\n}\n");
        UseStmt u = (UseStmt) s.getWorkflows().get(0).getStatements().get(0);
        assertThat(u.getTool()).isEqualTo("Notify");
        assertThat(u.getArgs()).hasSize(2);
        assertThat(u.isApprove()).isTrue();
        assertThat(u.getApproveHeading()).isEqualTo("Post it?");
        assertThat(u.getVariableName()).isEqualTo("receipt");
        assertThat(u.getRetryCount()).isEqualTo(1);
        assertThat(u.getBackoffMillis()).isEqualTo(2000);
        assertThat(u.getTimeoutMillis()).isEqualTo(30000);
        assertThat(u.getOnDeclined()).hasSize(1);
        assertThat(u.getOnFailure()).hasSize(1);
    }

    @Test
    void useIsStillAnOrdinaryWordElsewhere() {
        LoomScript s = parse(SUBSTITUTION + "tool Search { use: duckduckgo }\nworkflow Main(use) { set use = \"x\"\n  return use }\n");
        assertThat(s.getTools()).hasSize(1);
        assertThat(s.getWorkflows()).hasSize(1);
    }

    @Test
    void onDeclinedWithoutApproveIsRefused() {
        assertThatThrownBy(() -> parse("workflow Main(d) { use Notify(text = d) -> r on_declined { note \"x\" } }"))
                .hasMessageContaining("on_declined is for a step a person approves");
    }

    @Test
    void callsTheToolWithTheResolvedArgumentsAndBindsTheResult() {
        Notify n = new Notify();
        Run r = run(SUBSTITUTION + "workflow Main(draft) {\n  use Notify(text = \"News: {draft}\") -> receipt\n  return receipt\n}\n", n, "yes", null);
        assertThat(r.error()).isNull();
        assertThat(n.calls).containsExactly(Map.of("text", "News: Launch is Friday"));
        assertThat(r.vars().get("receipt")).isEqualTo("sent");
        assertThat(r.events().stream().anyMatch(e -> e.type().equals(TraceEvent.TOOL_CALL) && "use".equals(e.data().get("by")))).isTrue();
    }

    @Test
    void aToolThatReturnsJsonGivesAnObject() {
        Notify n = new Notify();
        n.reply = "{\"id\": 7, \"ok\": true}";
        Run r = run(SUBSTITUTION + "workflow Main(draft) {\n  use Notify(text = draft) -> receipt\n  set id = \"{receipt.id}\"\n  return id\n}\n", n, "yes", null);
        assertThat(r.vars().get("id")).isEqualTo("7");
    }

    @Test
    void anUnknownOrMissingArgumentIsNamedWithTheParametersTheToolTakes() {
        Notify n = new Notify();
        Run unknown = run(SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft, colour = \"red\") -> r }\n", n, "yes", null);
        assertThat(unknown.error()).hasMessageContaining("unknown argument colour").hasMessageContaining("takes text, channel");
        Run missing = run(SUBSTITUTION + "workflow Main(draft) { use Notify(channel = \"team\") -> r }\n", n, "yes", null);
        assertThat(missing.error()).hasMessageContaining("missing required argument text");
        assertThat(n.calls).isEmpty();
    }

    @Test
    void retryTriesAgainAfterAFailureAndOnFailureRunsWhenItsOut() {
        Notify n = new Notify();
        n.failTimes.set(1);
        Run ok = run(SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) -> r retry 1 }\n", n, "yes", null);
        assertThat(ok.error()).isNull();
        assertThat(n.calls).hasSize(1);
        Notify bad = new Notify();
        bad.failTimes.set(5);
        Run failed = run(SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) -> r on_failure { note \"may not have been sent: {_error}\" } }\n", bad, "yes", null);
        assertThat(failed.error()).isNull();
        assertThat(failed.notes()).anyMatch(m -> m.startsWith("may not have been sent: ") && m.contains("channel down"));
    }

    @Test
    void aResumedRunDoesNotCallTheToolAgain() {
        Notify n = new Notify();
        String src = SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) -> r }\n";
        Run first = run(src, n, "yes", null);
        Run again = run(src, n, "yes", first.journal());
        assertThat(again.error()).isNull();
        assertThat(n.calls).hasSize(1);
    }

    @Test
    void anUnknownToolIsACheckError() {
        LoomScript s = parse(SUBSTITUTION + "workflow Main(d) { use Nowhere(text = d) -> r }\n");
        HarnessExecutor ex = new HarnessExecutor(s, new ToolRegistry(), m -> null);
        var problems = new io.github.llm4j.loom.execution.ScriptValidator().validate(s, ex.validationContext());
        assertThat(problems).anyMatch(p -> p.message().contains("Nowhere is not a tool"));
    }

    @Test
    void retryOnAToolThatChangesThingsAndMayActTwiceIsRefused() {
        LoomScript s = parse(SUBSTITUTION + "tool Hook { use: webhook  url: \"https://example.com/h\" }\nworkflow Main(d) { use Hook(text = d) -> r retry 1 }\n");
        HarnessExecutor ex = new HarnessExecutor(s, new ToolRegistry(), m -> null);
        var problems = new io.github.llm4j.loom.execution.ScriptValidator().validate(s, ex.validationContext());
        assertThat(problems).anyMatch(p -> p.message().contains("not marked safe to repeat"));
    }

    // ---- R2: approve

    @Test
    void approveAsksOnceShowingTheExactArgumentsAndAYesRunsTheStep() {
        Notify n = new Notify();
        Run r = run(SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) approve \"Post this?\" -> r }\n", n, "yes", null);
        assertThat(r.error()).isNull();
        assertThat(r.asked()).hasSize(1);
        assertThat(r.asked().get(0)).contains("Post this?").contains("Launch is Friday").contains("Notify");
        assertThat(n.calls).hasSize(1);
    }

    @Test
    void aNoRunsOnDeclinedBindsNothingAndTheRunGoesOn() {
        Notify n = new Notify();
        Run r = run(SUBSTITUTION + "workflow Main(draft) {\n  use Notify(text = draft) approve -> r on_declined { note \"kept: {draft}\" }\n  note \"after\"\n}\n", n, "no", null);
        assertThat(r.error()).isNull();
        assertThat(n.calls).isEmpty();
        assertThat(r.vars()).doesNotContainKey("r");
        assertThat(r.notes()).containsExactly("kept: Launch is Friday", "after");
    }

    @Test
    void aNoWithoutOnDeclinedFailsTheStepAndOnFailureRuns() {
        Notify n = new Notify();
        Run r = run(SUBSTITUTION + "workflow Main(draft) {\n  use Notify(text = draft) approve -> r on_failure { note \"stopped: {_error}\" }\n}\n", n, "no", null);
        assertThat(n.calls).isEmpty();
        assertThat(r.notes()).anyMatch(m -> m.startsWith("stopped: ") && m.contains("not approved"));
    }

    @Test
    void anythingButAYesIsANo() {
        for (String answer : new String[] {"", "maybe", "later"}) {
            Notify n = new Notify();
            Run r = run(SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) approve -> r on_declined { note \"no\" } }\n", n, answer, null);
            assertThat(n.calls).as(answer).isEmpty();
        }
    }

    @Test
    void aResumedRunNeverAsksAgainForAnAnsweredStepAndNeverRunsAStepThatWasDeclined() {
        Notify n = new Notify();
        String src = SUBSTITUTION + "workflow Main(draft) { use Notify(text = draft) approve -> r on_declined { note \"declined\" } }\n";
        Run first = run(src, n, "no", null);
        Run again = run(src, n, "yes", first.journal());
        assertThat(again.asked()).isEmpty();
        assertThat(n.calls).isEmpty();
        assertThat(again.notes()).containsExactly("declined");
        Notify m = new Notify();
        Run ok = run(src, m, "yes", null);
        Run okAgain = run(src, m, "no", ok.journal());
        assertThat(okAgain.asked()).isEmpty();
        assertThat(m.calls).hasSize(1);
    }

    @Test
    void approveOnRunAsksOnceForAnyTaskAndTakesOnDeclined() {
        Notify n = new Notify();
        Run yes = run(SUBSTITUTION + "workflow Main(draft) { run Pay(amount = 5) approve -> paid }\n", n, "yes", null);
        assertThat(yes.asked()).hasSize(1);
        assertThat(yes.asked().get(0)).contains("Task Pay").contains("amount");
        assertThat(yes.vars()).containsKey("paid");
        Run no = run(SUBSTITUTION + "workflow Main(draft) {\n  run Pay(amount = 5) approve \"Pay 5?\" -> paid on_declined { note \"not paid\" }\n}\n", n, "no", null);
        assertThat(no.vars()).doesNotContainKey("paid");
        assertThat(no.notes()).containsExactly("not paid");
        assertThat(no.asked().get(0)).startsWith("Pay 5?");
    }
}
