package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.NotifyStmt;
import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.SignalWaitStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WaitStmt;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 34c and guard G30 (D17): {@code notify}, {@code signal} and {@code parent} are contextual.
 * Each is a keyword in the one shape it has, an ordinary name as a variable, and plain text in a
 * string; a script that already used them as names keeps its meaning.
 */
class SignalParseTest {

    private static List<Statement> body(String statements) {
        return StartParseTest.parse("workflow Main(q) {\n" + statements + "\n}").getWorkflows().get(0).getStatements();
    }

    // ---- keyword: notify ----

    @Test
    void notifyAHandleWithANameAndAnOptionalTextAndStickiness() {
        List<Statement> s = body("notify work \"go\"\nnotify work \"go\" \"use the 2025 data\"\nnotify work \"approved\" sticky\nnotify work \"a\" \"t\" sticky");

        NotifyStmt plain = (NotifyStmt) s.get(0);
        assertThat(plain.getTarget().name()).isEqualTo("work");
        assertThat(plain.toParent()).isFalse();
        assertThat(plain.getName()).isEqualTo("go");
        assertThat(plain.getText()).isNull();
        assertThat(plain.isSticky()).isFalse();
        assertThat(((NotifyStmt) s.get(1)).getText()).isEqualTo("use the 2025 data");
        assertThat(((NotifyStmt) s.get(2)).isSticky()).isTrue();
        assertThat(((NotifyStmt) s.get(2)).getText()).isNull();
        assertThat(((NotifyStmt) s.get(3)).isSticky()).isTrue();
        assertThat(((NotifyStmt) s.get(3)).getText()).isEqualTo("t");
    }

    @Test
    void notifyAKeyedHandleAndNotifyParent() {
        List<Statement> s = body("notify work[\"a\"] \"go\"\nnotify work[t.name] \"go\"\nnotify parent \"stuck\" \"no sources for {q}\"");

        assertThat(((NotifyStmt) s.get(0)).getTarget().key()).isEqualTo("a");
        assertThat(((NotifyStmt) s.get(1)).getTarget().isKeyed()).isTrue();
        NotifyStmt up = (NotifyStmt) s.get(2);
        assertThat(up.toParent()).isTrue();
        assertThat(up.getTarget()).isNull();
        assertThat(up.getText()).isEqualTo("no sources for {q}");
    }

    // ---- keyword: wait signal, in a child and in a parent ----

    @Test
    void aChildWaitsForASignalAndAParentWaitsForOneFromAChild() {
        List<Statement> s = body("wait signal \"go\" timeout 10m -> text\nwait work signal \"stuck\" timeout 5m -> msg\nwait work[\"a\"] signal \"x\" timeout 1m -> y");

        SignalWaitStmt child = (SignalWaitStmt) s.get(0);
        assertThat(child.getTarget()).isNull();
        assertThat(child.getName()).isEqualTo("go");
        assertThat(child.getTimeoutMillis()).isEqualTo(600_000L);
        assertThat(child.getResultVariable()).isEqualTo("text");
        SignalWaitStmt parent = (SignalWaitStmt) s.get(1);
        assertThat(parent.getTarget().name()).isEqualTo("work");
        assertThat(parent.getName()).isEqualTo("stuck");
        assertThat(((SignalWaitStmt) s.get(2)).getTarget().key()).isEqualTo("a");
    }

    @Test
    void aWhenSignalRuleInASuperviseBlock() {
        SuperviseStmt block =
                (SuperviseStmt) body("supervise work { when signal \"stuck\" { steer \"Try another source.\" once } }").get(0);

        assertThat(block.getSignals()).hasSize(1);
        assertThat(block.getSignals().get(0).name()).isEqualTo("stuck");
        assertThat(block.getSignals().get(0).once()).isTrue();
        assertThat(block.getSignals().get(0).actions()).hasSize(1);
    }

    // ---- variable: nothing that was a name changes ----

    @Test
    void notifySignalAndParentStayOrdinaryNamesEverywhereElse() {
        List<Statement> s =
                body(
                        """
                        set notify = "a"
                        set signal = "b"
                        set parent = "c"
                        note "{notify} {signal} {parent}"
                        set copy = signal.text
                        """);

        assertThat(s).allMatch(x -> x instanceof SetStmt || x instanceof io.github.llm4j.loom.ast.NoteStmt);
        assertThat(((SetStmt) s.get(1)).getName()).isEqualTo("signal");
    }

    @Test
    void aHandleCalledSignalIsStillWaitedForAsAnyHandle() {
        List<Statement> s = body("start Child(x = q) budget { tokens: 1 } -> signal\nwait signal timeout 5m -> r\nwait all signal timeout 5m");

        assertThat(s.get(1)).as("signal is the handle: no string follows it").isInstanceOf(WaitStmt.class);
        assertThat(((WaitStmt) s.get(1)).getTarget().name()).isEqualTo("signal");
        assertThat(s.get(2)).isInstanceOf(WaitStmt.class);
    }

    @Test
    void aHandleCalledParentOrNotifyCanStillBeSupervisedCancelledAndWaitedFor() {
        List<Statement> s = body("start Child(x = q) budget { tokens: 1 } -> notify\nwait notify timeout 5m -> r\ncancel notify");

        assertThat(s.get(1)).isInstanceOf(WaitStmt.class);
        assertThat(s.get(2)).isInstanceOf(io.github.llm4j.loom.ast.CancelStmt.class);
    }

    @Test
    void anAgentOrWorkflowCalledNotifyOrSignalWorks() {
        LoomScript script = StartParseTest.parse("agent signal { model: \"m\" system: \"s\" }\nworkflow notify(q) { delegate \"x\" to signal -> r }\nworkflow Main(q) { call notify(q = q) -> r }");

        assertThat(script.getWorkflows()).extracting("name").containsExactly("notify", "Main");
    }

    // ---- text: never a keyword, never altered ----

    @Test
    void theWordsInsideTextAreNeverKeywords() {
        List<Statement> s = body("note \"notify work \\\"go\\\"\"\nset s = \"wait signal \\\"x\\\" timeout 5m\"\nnote \"when signal \\\"x\\\"\"");

        assertThat(s).hasSize(3).noneMatch(x -> x instanceof NotifyStmt || x instanceof SignalWaitStmt);
    }

    // ---- what is refused, and what to do ----

    @Test
    void notifyNeedsANameInQuotes() {
        assertThatThrownBy(() -> body("notify work")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> body("notify work go")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aWhenSignalRuleNeedsAnActionAndANameInQuotes() {
        assertThatThrownBy(() -> body("supervise work { when signal stuck { steer \"x\" } }")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> body("supervise work { when signal \"stuck\" { } }")).hasMessageContaining("does nothing");
    }

    @Test
    void aWhenSignalRuleStillTakesOnlyFixedTextActions() {
        assertThatThrownBy(() -> body("supervise work { when signal \"stuck\" { steer \"{payload}\" } }"))
                .hasMessageContaining("steer");
    }
}
