package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.CheckStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CheckStatementParsesTest {

    static CheckStmt check(String statement) {
        LoomScript s = PolicyParsesTest.parse("workflow Main(q) {\n  " + statement + "\n}\n");
        Statement first = s.getWorkflows().get(0).getStatements().get(0);
        return (CheckStmt) first;
    }

    @Test
    void theFullFormReadsEveryPart() {
        CheckStmt c =
                check(
                        "check reply against HouseRules, Legal using Compliance -> verdict retry 2 timeout 20s"
                                + " on_violation { rewrite with Editor } on_unavailable { replace with \"hold\" }"
                                + " on_failure { note \"failed\" }");
        assertThat(c.getSubject()).isEqualTo("reply");
        assertThat(c.getPolicies()).containsExactly("HouseRules", "Legal");
        assertThat(c.getEvaluator()).isEqualTo("Compliance");
        assertThat(c.getVerdictVariable()).isEqualTo("verdict");
        assertThat(c.getRetryCount()).isEqualTo(2);
        assertThat(c.getTimeoutMillis()).isEqualTo(20_000);
        assertThat(c.getOnViolation()).isEqualTo(new CheckStmt.Rewrite("Editor"));
        assertThat(c.getOnUnavailable()).isEqualTo(new CheckStmt.Replace("hold"));
        assertThat(c.getOnFailure()).hasSize(1);
    }

    @Test
    void aFieldPathIsASubjectAndEveryActionParses() {
        assertThat(check("check out.text against P using E").getSubject()).isEqualTo("out.text");
        assertThat(check("check r against P using E on_violation { ask person }").getOnViolation())
                .isEqualTo(new CheckStmt.AskPerson());
        assertThat(check("check r against P using E on_violation { fail }").getOnViolation())
                .isEqualTo(new CheckStmt.Fail());
    }

    @Test
    void withoutOnViolationTheActionIsFailAndSoIsOnUnavailable() {
        CheckStmt c = check("check r against P using E");
        assertThat(c.getOnViolation()).isEqualTo(new CheckStmt.Fail());
        assertThat(c.getOnUnavailable()).isEqualTo(new CheckStmt.Fail());
        assertThat(c.getVerdictVariable()).isNull();
    }

    @Test
    void onViolationHoldsExactlyOneAction() {
        assertThatThrownBy(() -> check("check r against P using E on_violation { ask person fail }"))
                .hasMessageContaining("exactly one action");
        assertThatThrownBy(() -> check("check r against P using E on_violation { }"))
                .hasMessageContaining("exactly one action");
    }

    @Test
    void anUnavailableEvaluatorCannotBeRewrittenAround() {
        assertThatThrownBy(() -> check("check r against P using E on_unavailable { rewrite with A }"))
                .hasMessageContaining("cannot rewrite");
    }

    @Test
    void theEvaluatorMustBeNamed() {
        assertThatThrownBy(() -> check("check r against P"))
                .hasMessageContaining("Name the evaluator");
    }

    @Test
    void checkStaysAnOrdinaryWordAndTheTrustFormStillParses() throws Exception {
        LoomScript s =
                PolicyParsesTest.parse(
                        "workflow Main(check) { set check = \"x\" return check }\n");
        assertThat(s.getWorkflows()).hasSize(1);
        Path starter = Path.of("../starters/15-earned-autonomy/main.loom");
        assertThat(PolicyParsesTest.parse(Files.readString(starter)).getDecisions())
                .as("`check 5% of cases` inside trust")
                .isNotEmpty();
    }
}
