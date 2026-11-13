package io.github.llm4j.loom.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 34c, D14 and guard G22/LA30: a workflow that reads a signal lets text from another run
 * enter its variables, so it is an input channel the audit names, with the signal and the side that
 * can send it. There is no rule for sending: the parent already gets the child's result.
 */
class SignalAuditTest {

    private static final String BASE =
            """
            agent Writer { model: "m" system: "s" }
            budget { tokens: 1000 }
            """;

    private static List<Finding> la30(String source) {
        LoomScript script = new LoomParser(new Lexer(BASE + source).tokenize()).parseScript();
        return SecurityAudit.audit(script, "t.loom").findings().stream().filter(f -> f.rule().equals("LA30")).toList();
    }

    @Test
    void aChildThatWaitsForASignalNamesTheSignalAndTheWorkflowsThatCanSendIt() {
        var found =
                la30(
                        """
                        workflow Worker(x) { wait signal "go" timeout 5m -> text  return "ok" }
                        workflow Main(q) { start Worker(x = q) budget { tokens: 10 } -> w  notify w "go" "now"  wait w timeout 1m }
                        """);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).risk()).contains("go").contains("Main").contains("parent");
        assertThat(found.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(found.get(0).owasp()).contains(Owasp.LLM01);
        assertThat(found.get(0).where()).contains("Worker");
    }

    @Test
    void aParentThatWaitsForOrRulesOnAChildsSignalNamesTheChildThatCanSendIt() {
        var found =
                la30(
                        """
                        workflow Worker(x) { notify parent "stuck" "no sources"  return "ok" }
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> w
                            supervise w { when signal "stuck" { wrap_up } }
                            wait w signal "done" timeout 1m -> text
                        }
                        """);

        assertThat(found).hasSize(2);
        assertThat(found).allMatch(f -> f.risk().contains("Worker"));
        assertThat(found).anyMatch(f -> f.risk().contains("stuck"));
        assertThat(found).anyMatch(f -> f.risk().contains("done"));
    }

    @Test
    void aSignalWhoseTextReachesAPromptIsMediumBecauseTextFromAnotherRunEntersAModelsContext() {
        var reaches =
                la30(
                        """
                        workflow Worker(x) { wait signal "go" timeout 5m -> text  delegate "Act on: {text}" to Writer -> r  return r }
                        workflow Main(q) { start Worker(x = q) budget { tokens: 10 } -> w  wait w timeout 1m }
                        """);
        var unused =
                la30(
                        """
                        workflow Worker(x) { wait signal "go" timeout 5m -> text  return "done" }
                        workflow Main(q) { start Worker(x = q) budget { tokens: 10 } -> w  wait w timeout 1m }
                        """);

        assertThat(reaches.get(0).severity()).isEqualTo(Severity.MEDIUM);
        assertThat(reaches.get(0).risk()).contains("prompt");
        assertThat(unused.get(0).severity()).isEqualTo(Severity.INFO);
    }

    private static Severity worker(String body) {
        return la30(
                        "workflow Worker(x) { wait signal \"go\" timeout 5m -> text  " + body + " }\n"
                                + "workflow Main(q) { start Worker(x = q) budget { tokens: 10 } -> w  wait w timeout 1m }\n")
                .get(0)
                .severity();
    }

    @Test
    void aCopyOfTheTextThatReachesAPromptIsFollowedAndIsMedium() {
        assertThat(worker("set copy = text  delegate \"Act on: {copy}\" to Writer -> r  return r")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void aCopyBuiltIntoLargerTextIsFollowed() {
        assertThat(worker("set note = \"Instruction from a helper: {text}\"  delegate \"{note}\" to Writer -> r  return r"))
                .isEqualTo(Severity.MEDIUM);
    }

    @Test
    void aChainOfCopiesIsFollowedToTheEnd() {
        assertThat(worker("set first = text  set second = first  set third = \"{second}\"  delegate \"{third}\" to Writer -> r  return r"))
                .isEqualTo(Severity.MEDIUM);
    }

    @Test
    void aCopyThatNothingReadsLeavesTheFindingAtInfo() {
        assertThat(worker("set copy = text  set again = copy  return \"done\"")).isEqualTo(Severity.INFO);
    }

    @Test
    void aCopyBuiltFromOtherThingsDoesNotCarryTheTextAndAPromptThatReadsItStaysInfo() {
        assertThat(worker("set other = \"fixed\"  delegate \"Act on: {other}\" to Writer -> r  return r")).isEqualTo(Severity.INFO);
    }

    @Test
    void aVariableThatOnlyStartsWithTheSignalsNameIsNotTheSignalsText() {
        assertThat(worker("set textual = \"fixed\"  delegate \"Act on: {textual}\" to Writer -> r  return r")).isEqualTo(Severity.INFO);
    }

    @Test
    void aFieldOfTheTextOrACopyAlsoCounts() {
        assertThat(worker("delegate \"Act on: {text.body}\" to Writer -> r  return r")).isEqualTo(Severity.MEDIUM);
        assertThat(worker("set copy = text  delegate \"Act on: {copy.body}\" to Writer -> r  return r")).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void theCopyItselfIsNotTakenForTheTextReachingAPromptWhenOnlyItIsBuilt() {
        assertThat(worker("set note = \"Heard: {text}\"  return \"done\"")).isEqualTo(Severity.INFO);
    }

    @Test
    void sendingASignalIsNotAFindingBecauseTheChannelIsNotNew() {
        assertThat(
                        la30(
                                """
                                workflow Worker(x) { notify parent "stuck" "x"  return "ok" }
                                workflow Main(q) { start Worker(x = q) budget { tokens: 10 } -> w  notify w "go" "x"  wait w timeout 1m }
                                """))
                .isEmpty();
    }

    @Test
    void aScriptWithNoSignalsHasNoLa30() {
        assertThat(la30("workflow Main(q) { note \"x\" }")).isEmpty();
    }

    @Test
    void theRuleIsListedInTheAuditPageAndMappedToTheOwaspPage() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("..", "..", "..", "docs", "security").toAbsolutePath().normalize();
        assertThat(java.nio.file.Files.readString(root.resolve("weave-audit.md"))).contains("LA30");
        assertThat(java.nio.file.Files.readString(root.resolve("owasp-llm-top-10.md"))).contains("LA30");
    }
}
