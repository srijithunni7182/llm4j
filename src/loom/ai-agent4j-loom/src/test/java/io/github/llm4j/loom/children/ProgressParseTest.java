package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ReportStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.SetStmt;
import io.github.llm4j.loom.ast.SuperviseStmt;
import io.github.llm4j.loom.ast.WorkflowDef;
import org.junit.jupiter.api.Test;

/**
 * Phase 34b, declared progress, and guard G30 (D17): {@code report} and {@code progress} are
 * contextual. The parser tells a keyword, a variable and text apart by position and shape, so
 * every word is tested in all three contexts and no existing script changes meaning.
 */
class ProgressParseTest {

    private static LoomScript parse(String source) {
        return StartParseTest.parse(source);
    }

    // ---- the keyword context: the one shape each word has ----

    @Test
    void aWorkflowDeclaresItsProgressAfterTheParametersAndReportsIt() {
        LoomScript script =
                parse(
                        """
                        workflow Research(topic) progress { stage: string, done: number, ok: boolean, phase: enum["a", "b"] } {
                            report { stage: "searching", done: 0, ok: true, phase: "a" }
                        }
                        """);
        WorkflowDef w = script.getWorkflows().get(0);

        assertThat(w.getProgress().fields().keySet()).containsExactly("stage", "done", "ok", "phase");
        assertThat(w.getProgress().fields().get("done").getType()).isEqualTo(SchemaDef.Type.NUMBER);
        assertThat(w.getProgress().fields().get("phase").getType()).isEqualTo(SchemaDef.Type.ENUM);
        assertThat(w.getStatements().get(0)).isInstanceOf(ReportStmt.class);
    }

    @Test
    void aProgressAssertionInASuperviseBlockReadsAsAFieldThatChangesWithinATime() {
        LoomScript script =
                parse(
                        """
                        workflow Main(q) {
                            supervise work { assert progress.done changes within 3m { steer "go on" once } }
                        }
                        """);
        var block = (SuperviseStmt) script.getWorkflows().get(0).getStatements().get(0);

        assertThat(block.getCode()).hasSize(1);
        assertThat(block.getCode().get(0).kind()).isEqualTo(SuperviseStmt.AssertionKind.CHANGES_WITHIN);
        assertThat(block.getCode().get(0).name()).isEqualTo("done");
        assertThat(block.getCode().get(0).withinMillis()).isEqualTo(180_000L);
    }

    // ---- the variable context: nothing that was a variable changes ----

    @Test
    void reportAndProgressStayOrdinaryVariablesEverywhereElse() {
        LoomScript script =
                parse(
                        """
                        agent A { model: "m" system: "s" }
                        workflow Main(q) {
                            delegate "go" to A -> report
                            set progress = "half"
                            set report = report
                            note "{report} at {progress}"
                            set copy = report.summary
                            delegate "summarise {report}" to A -> progress
                            return progress
                        }
                        """);
        WorkflowDef w = script.getWorkflows().get(0);

        assertThat(w.getProgress()).as("no declaration: progress was not after a ')' and before '{'").isNull();
        assertThat(w.getStatements()).noneMatch(s -> s instanceof ReportStmt);
        assertThat(w.getStatements().get(1)).isInstanceOf(SetStmt.class);
        assertThat(((SetStmt) w.getStatements().get(1)).getName()).isEqualTo("progress");
    }

    @Test
    void aVariableCalledReportMayBeFollowedByABraceOnTheNextStatementOnlyIfThatIsAReport() {
        // `-> report` ends a statement; a later `report {` on a new line is the statement
        LoomScript script =
                parse(
                        """
                        agent A { model: "m" system: "s" }
                        workflow Research(q) progress { stage: string } {
                            delegate "go" to A -> report
                            report { stage: "done" }
                        }
                        """);

        assertThat(script.getWorkflows().get(0).getStatements().get(1)).isInstanceOf(ReportStmt.class);
    }

    @Test
    void aWorkflowParameterMayBeCalledProgressOrReport() {
        LoomScript script = parse("workflow Main(progress, report) { set x = progress }");

        assertThat(script.getWorkflows().get(0).getParameters()).containsExactly("progress", "report");
        assertThat(script.getWorkflows().get(0).getProgress()).isNull();
    }

    // ---- the text context: a word in a string is never a keyword and never altered ----

    @Test
    void theWordsInsideTextAreNeverKeywords() {
        LoomScript script =
                parse(
                        """
                        workflow Main(q) {
                            note "report { stage: 1 }"
                            set s = "progress { a: string }"
                            note "assert progress.done changes within 3m"
                        }
                        """);

        assertThat(script.getWorkflows().get(0).getStatements()).hasSize(3).noneMatch(s -> s instanceof ReportStmt);
    }

    // ---- what is refused, with a message that says what to do ----

    @Test
    void progressIsOneFlatLevelOfStringNumberBooleanOrEnum() {
        assertThatThrownBy(() -> parse("workflow W(q) progress { items: list<string> } { note \"x\" }"))
                .hasMessageContaining("progress")
                .hasMessageContaining("items");
        assertThatThrownBy(() -> parse("workflow W(q) progress { inner: { a: string } } { note \"x\" }"))
                .hasMessageContaining("flat");
        assertThatThrownBy(() -> parse("workflow W(q) progress { } { note \"x\" }")).hasMessageContaining("at least one");
    }

    @Test
    void aReportFieldIsGivenOnceAndHoldsAFlatValue() {
        assertThatThrownBy(() -> parse("workflow W(q) progress { a: string } { report { a: \"x\", a: \"y\" } }"))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> parse("workflow W(q) progress { a: string } { report { a: { b: \"x\" } } }"))
                .hasMessageContaining("flat");
    }

    @Test
    void aProgressFieldIsGivenOnce() {
        assertThatThrownBy(() -> parse("workflow W(q) progress { a: string, a: number } { note \"x\" }"))
                .hasMessageContaining("twice");
    }
}
