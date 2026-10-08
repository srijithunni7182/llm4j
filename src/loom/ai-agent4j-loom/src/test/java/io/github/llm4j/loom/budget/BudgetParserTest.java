package io.github.llm4j.loom.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.BudgetDef;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.LoopStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verification plan, Requirement 5 (V5.1–V5.5). */
class BudgetParserTest {

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    static List<Statement> main(String body) {
        return parse("agent A { model: \"m\" }\nagent B { model: \"m\" }\nworkflow Main() {\n" + body + "\n}")
                .getWorkflows().get(0).getStatements();
    }

    @Test
    void v5_1_topLevelBudgetBlock() {
        BudgetDef b = parse("budget { tokens: 200000  calls: 150  cost: \"$0.50\"  warn_at: 80% }").getBudget();
        assertThat(b.getTokens()).isEqualTo(200_000);
        assertThat(b.getCalls()).isEqualTo(150);
        assertThat(b.getCost()).isEqualByComparingTo("0.50");
        assertThat(b.getWarnAt()).isEqualTo(0.8);
        BudgetDef subset = parse("budget { calls: 5 }").getBudget();
        assertThat(subset.getCalls()).isEqualTo(5);
        assertThat(subset.getTokens()).isNull();
        assertThat(parse("budget { tokens: 10, calls: 2 }").getBudget().getCalls()).isEqualTo(2); // commas allowed
    }

    @Test
    void v5_2_agentBudgetWithPerCallCap() {
        BudgetDef b = parse("agent W { model: \"m\" budget { tokens: 20000 per_call: 2000 } }").getAgents().get(0).getBudget();
        assertThat(b.getTokens()).isEqualTo(20_000);
        assertThat(b.getPerCall()).isEqualTo(2000);
        assertThatThrownBy(() -> parse("budget { per_call: 10 }"))
                .hasMessageContaining("line 1").hasMessageContaining("per_call is only allowed in an agent");
    }

    @ParameterizedTest(name = "{0} with budget {1}")
    @CsvSource(delimiter = '|', value = {
            "delegate|5000 tokens|tokens|5000",
            "delegate|10 calls|calls|10",
            "delegate|\"$0.05\"|cost|0.05",
            "broadcast|5000 tokens|tokens|5000",
            "broadcast|10 calls|calls|10",
            "broadcast|\"$0.05\"|cost|0.05",
            "loop|5000 tokens|tokens|5000",
            "loop|10 calls|calls|10",
            "loop|\"$0.05\"|cost|0.05",
            "foreach|5000 tokens|tokens|5000",
            "foreach|10 calls|calls|10",
            "foreach|\"$0.05\"|cost|0.05"})
    void v5_3_statementBudgetModifiers(String statement, String modifier, String dimension, String value) {
        String body = switch (statement) {
            case "delegate" -> "delegate \"x\" to A -> out budget " + modifier;
            case "broadcast" -> "broadcast \"x\" to [A, B] -> out budget " + modifier;
            case "loop" -> "loop until (done == \"yes\") max 3 budget " + modifier + " { delegate \"x\" to A -> done }";
            default -> "for each item in items budget " + modifier + " { delegate \"{item}\" to A -> out }";
        };
        Statement s = main(body).get(0);
        BudgetDef b = s instanceof DelegateStmt d ? d.getBudget()
                : s instanceof BroadcastStmt br ? br.getBudget()
                : s instanceof LoopStmt l ? l.getBudget()
                : ((ForEachStmt) s).getBudget();
        switch (dimension) {
            case "tokens" -> assertThat(b.getTokens()).isEqualTo(Long.parseLong(value));
            case "calls" -> assertThat(b.getCalls()).isEqualTo(Long.parseLong(value));
            default -> assertThat(b.getCost()).isEqualByComparingTo(value);
        }
    }

    @Test
    void modifiersMixInAnyOrderWithRetryAndTimeouts() {
        DelegateStmt d = (DelegateStmt) main("delegate \"x\" to A -> out budget 300 tokens retry 3 timeout 5s budget 2 calls "
                + "on_failure { note \"{_error}\" }").get(0);
        assertThat(d.getBudget().getTokens()).isEqualTo(300);
        assertThat(d.getBudget().getCalls()).isEqualTo(2);
        assertThat(d.getRetryCount()).isEqualTo(3);
        assertThat(d.getTimeoutMillis()).isEqualTo(5000);
        assertThat(d.getOnFailure()).hasSize(1);
    }

    @Test
    void v5_4_budgetIsStillAVariableName() {
        List<Statement> body = main("delegate \"x\" to A -> budget\ndelegate \"use {budget}\" to B -> out");
        assertThat(((DelegateStmt) body.get(0)).getVariableName()).isEqualTo("budget");
        assertThat(((DelegateStmt) body.get(0)).getBudget()).isNull();
        assertThat(((DelegateStmt) body.get(1)).getPayload()).isEqualTo("use {budget}");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "budget { tokens: 0 }|tokens",
            "budget { tokens: -5 }|tokens",
            "budget { calls: 2.5 }|calls",
            "budget { cost: \"0.50\" }|currency symbol",
            "budget { cost: \"$0\" }|cost must be positive",
            "budget { warn_at: 0% }|warn_at",
            "budget { speed: 3 }|Unknown budget field 'speed'"})
    void v5_5_invalidValuesNameTheLineAndField(String source, String mentions) {
        assertThatThrownBy(() -> parse("\n" + source)).hasMessageContaining("line 2").hasMessageContaining(mentions);
    }

    @Test
    void onExhaustedNeedsABoundOrABudget() {
        assertThatThrownBy(() -> main("loop until (x == \"y\") { delegate \"x\" to A -> x } on_exhausted { note \"n\" }"))
                .hasMessageContaining("budget N tokens");
        assertThat(((LoopStmt) main("loop until (x == \"y\") budget 300 tokens { delegate \"x\" to A -> x } "
                + "on_exhausted { note \"n\" }").get(0)).getOnExhausted()).hasSize(1);
        assertThat(((ForEachStmt) main("for each i in items budget 300 tokens { delegate \"x\" to A -> x } "
                + "on_exhausted { note \"n\" }").get(0)).getOnExhausted()).hasSize(1);
        assertThatThrownBy(() -> main("for each i in items { delegate \"x\" to A -> x } on_exhausted { note \"n\" }"))
                .hasMessageContaining("needs a budget");
    }

    @Test
    void importsKeepTheFirstRunBudget() {
        LoomScript a = parse("budget { tokens: 10 }");
        a.merge(parse("budget { tokens: 99 }"));
        assertThat(a.getBudget().getTokens()).isEqualTo(10);
        LoomScript b = parse("agent A { model: \"m\" }");
        b.merge(parse("budget { calls: 3 }"));
        assertThat(b.getBudget().getCalls()).isEqualTo(3);
    }
}
