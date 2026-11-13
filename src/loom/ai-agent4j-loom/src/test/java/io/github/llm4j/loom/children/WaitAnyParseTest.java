package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.ast.WaitStmt;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 34d and guard G30 (D17): {@code any} is a keyword only directly after {@code wait}; everywhere else it is an ordinary name. */
class WaitAnyParseTest {

    private static List<Statement> body(String statements) {
        return StartParseTest.parse("workflow Main(q) {\n" + statements + "\n}").getWorkflows().get(0).getStatements();
    }

    @Test
    void waitAnyTakesAHandleOrAKeyedVariableATimeoutAndAnEventVariable() {
        List<Statement> s = body("wait any work timeout 2m -> event\nwait any work[\"a\"] timeout 1m -> e2");

        WaitStmt w = (WaitStmt) s.get(0);
        assertThat(w.isAny()).isTrue();
        assertThat(w.isAll()).isFalse();
        assertThat(w.getTarget().name()).isEqualTo("work");
        assertThat(w.getTimeoutMillis()).isEqualTo(120_000L);
        assertThat(w.getResultVariable()).isEqualTo("event");
        assertThat(((WaitStmt) s.get(1)).getTarget().key()).isEqualTo("a");
    }

    @Test
    void aHandleCalledAnyIsStillWaitedForAsAnyHandle() {
        List<Statement> s = body("start Child(x = q) budget { tokens: 1 } -> any\nwait any timeout 5m -> r\nwait all any timeout 5m");

        assertThat(((WaitStmt) s.get(1)).isAny()).as("any is the handle: a timeout follows it").isFalse();
        assertThat(((WaitStmt) s.get(1)).getTarget().name()).isEqualTo("any");
        assertThat(((WaitStmt) s.get(2)).isAll()).isTrue();
    }

    @Test
    void anyStaysAnOrdinaryNameAsAVariableAndInText() {
        List<Statement> s = body("set any = \"a\"\nnote \"{any} and wait any work timeout 1m\"\nset copy = any.thing");

        assertThat(s).hasSize(3).allMatch(x -> x instanceof SetStmt || x instanceof io.github.llm4j.loom.ast.NoteStmt);
    }

    @Test
    void waitAnyIsNotAlsoWaitAll() {
        assertThatThrownBy(() -> body("wait any all work timeout 1m -> e")).isInstanceOf(RuntimeException.class);
    }
}
