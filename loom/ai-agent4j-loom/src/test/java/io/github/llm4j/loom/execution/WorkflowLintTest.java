package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R6.1 to R6.4 of loom-onboarding: what a workflow stores and never reads. */
class WorkflowLintTest {

    private static List<WorkflowLint.Finding> lint(String workflowBody) {
        var script = new LoomParser(new Lexer("agent A { model: \"m\" system: \"s\" }\n" + workflowBody).tokenize()).parseScript();
        return script.getWorkflows().stream().flatMap(w -> WorkflowLint.check(w).stream()).toList();
    }

    private static List<String> messages(String body) {
        return lint(body).stream().map(WorkflowLint.Finding::message).toList();
    }

    @Test
    void r6_1_aResultThatIsStoredAndNeverReadIsReportedWithItsLine() {
        var findings = lint("workflow W() {\n    delegate \"x\" to A -> unused\n    note \"done\"\n}\n");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.message()).isEqualTo("unused is set here and never used");
            assertThat(f.line()).isEqualTo(3);
            assertThat(f.construct()).isEqualTo("workflow W");
        });
    }

    @Test
    void aResultReadAnywhereCounts_inAPromptAConditionANoteAFieldPathOrAHumanQuestion() {
        assertThat(messages("""
                workflow W() {
                    delegate "x" to A -> a
                    delegate "uses {a}" to A -> b
                    delegate "y" to A -> c expecting { verdict: string }
                    alt (c.verdict == "OK") { note "{b}" }
                    delegate "z" to A -> d
                    human_prompt "Is {d} fine?" -> e
                    loop until (e == "yes") max 2 { delegate "again" to A -> e }
                }
                """)).isEmpty();
    }

    @Test
    void aVariableIsNotReadJustBecauseItsNameIsPartOfALongerWord() {
        assertThat(messages("workflow W() {\n    delegate \"x\" to A -> draft\n    delegate \"the drafting phase, {drafts}\" to A -> other\n    note \"{other}\"\n}\n"))
                .containsExactly("draft is set here and never used");
    }

    @Test
    void aVariableReadInItsOwnStatementCountsSoARewriteLoopIsFine() {
        assertThat(messages("""
                workflow W() {
                    delegate "first" to A -> draft
                    delegate "fix {draft}" to A -> draft
                    note "{draft}"
                }
                """)).isEmpty();
    }

    @Test
    void anUnderscoreNameIsMeantToBeIgnoredAndATasksResultIsNotReported() {
        assertThat(messages("workflow W() {\n    delegate \"x\" to A -> _ignored\n    run Task() -> receipt\n}\n")).isEmpty();
    }

    @Test
    void parametersAreNotReportedAndNestedStatementsAreLooked_at() {
        var found = messages("""
                workflow W(topic) {
                    loop until (done == "yes") max 2 {
                        delegate "x" to A -> done
                        delegate "y" to A -> inner
                    }
                } 
                """);

        assertThat(found).containsExactly("inner is set here and never used");
    }

    @Test
    void r6_2_aQuestionWhoseAnswerIsNeverReadSaysSoInPlainWords() {
        var found = messages("workflow W() {\n    human_prompt \"Show the draft anyway? (yes/no)\" -> override\n    note \"done\"\n}\n");

        assertThat(found).containsExactly("the answer to this question is stored in override and never read, so the workflow does the same whatever the person says");
    }

    @Test
    void r6_2_theExhaustedLoopFromTheDogfoodingRunIsCaught() {
        var found = lint("""
                workflow Diagnose(problem) {
                    delegate "diagnose {problem}" to A -> draft
                    loop until (review.verdict == "APPROVED") max 3 {
                        delegate "review {draft}" to A -> review expecting { verdict: enum["APPROVED", "REJECTED"], issues: string }
                    } on_exhausted {
                        note "no version passed after {_loopRounds} rounds"
                        human_prompt "Show the last draft anyway? {review.issues}" -> override
                    }
                    note "Approved script:\\n{draft}"
                }
                """);

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.message()).contains("override").contains("whatever the person says");
            assertThat(f.line()).isEqualTo(8);
        });
    }

    @Test
    void r6_4_bothAnswersLeadingToTheSameStepsIsReported() {
        var found = messages("""
                workflow W() {
                    human_prompt "Approve?" -> ok
                    alt (ok == "yes") { note "sent" } else { note "sent" }
                }
                """);

        assertThat(found).containsExactly("both answers to the question lead to the same steps, so the question changes nothing");
    }

    @Test
    void differentStepsForTheTwoAnswersAreFine_andSoIsACommonStepAfterwards() {
        assertThat(messages("""
                workflow W() {
                    human_prompt "Approve?" -> ok
                    alt (ok == "yes") { note "sent" } else { note "held" }
                    note "end"
                }
                """)).isEmpty();
    }

    @Test
    void anAltOnSomethingOtherThanAHumanAnswerIsNotCompared() {
        assertThat(messages("""
                workflow W() {
                    delegate "x" to A -> r
                    alt (r == "yes") { note "a" } else { note "a" }
                }
                """)).isEmpty();
    }

    @Test
    void theFindingsComeInLineOrder() {
        var found = lint("workflow W() {\n    delegate \"x\" to A -> first\n    delegate \"y\" to A -> second\n}\n");

        assertThat(found).extracting(WorkflowLint.Finding::line).isSorted();
        assertThat(found).hasSize(2);
    }

    @Test
    void theVariableNamedResultIsWhatACalledWorkflowHandsBackSoItIsNotReportedAsUnused() {
        assertThat(messages("workflow Sub(x) {\n    delegate \"do {x}\" to A -> result\n}\n")).isEmpty();
        assertThat(messages("workflow Sub(x) {\n    delegate \"do {x}\" to A -> other\n}\n")).containsExactly("other is set here and never used");
    }
}
