package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.DecideStmt;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Looking a decision up by name, on its own without a whole run. */
class DeciderLookupTest {

    @TempDir Path dir;

    private static final String SCRIPT =
            """
            agent Triager {
                model: "m"
                system: "You are Triager."
                output_schema: { choice: string, reasoning: string, confidence: number }
            }
            decision Refund {
                proposed by: Triager
                choices:     approve, reject
                ask:         support-lead
                trust {
                    start at watch
                    never go above suggest
                }
            }
            workflow Triage(tier) {
                decide Refund -> verdict
            }
            """;

    private Decider decider() {
        return new Decider(new ScriptedRun(dir).executor(SCRIPT));
    }

    @Test
    void findsADeclaredDecisionByName() {
        assertThat(decider().decisionOf(new DecideStmt("Refund", "verdict")).getName())
                .isEqualTo("Refund");
    }

    @Test
    void anUndeclaredDecisionIsAnErrorThatNamesIt() {
        assertThatThrownBy(() -> decider().decisionOf(new DecideStmt("Missing", "verdict")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("decision Missing is not defined");
    }
}
