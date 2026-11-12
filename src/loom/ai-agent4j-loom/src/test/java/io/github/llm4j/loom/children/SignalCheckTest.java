package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.ScriptValidator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 34c: what {@code weave check} says about signals. Errors where a script cannot be right
 * (a handle this run did not start, a name or payload that can never be sent, a wait with no
 * timeout, two waits for one name that can be active at once, a signal's payload becoming a steer's
 * text) and warnings where it may be (a child that signals a parent nobody has).
 */
class SignalCheckTest {

    private static final String BASE =
            """
            agent Researcher { model: "m" system: "s" steerable: true streaming: true }
            workflow Child(x) { delegate "go" to Researcher -> r  return r }
            """;

    private static List<ScriptValidator.Problem> check(String source) {
        return new ScriptValidator()
                .validate(StartParseTest.parse(BASE + source), new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of(".")));
    }

    private static List<String> errors(List<ScriptValidator.Problem> p) {
        return p.stream().filter(x -> x.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::message).toList();
    }

    private static List<String> warnings(List<ScriptValidator.Problem> p) {
        return p.stream().filter(x -> x.severity() == ScriptValidator.Severity.WARNING).map(ScriptValidator.Problem::message).toList();
    }

    private static final String START = "start Child(x = q) budget { tokens: 10 } -> work\n";

    private static List<ScriptValidator.Problem> main(String body) {
        return check("workflow Main(q) {\n" + START + body + "\nwait work timeout 1m\n}");
    }

    // ---- who may signal whom (D14.4) ----

    @Test
    void aParentMayNotifyOnlyAHandleItStarted() {
        assertThat(errors(main("notify work \"go\" \"x\""))).noneMatch(e -> e.contains("handle"));
        assertThat(errors(main("notify nobody \"go\" \"x\""))).anyMatch(e -> e.contains("nobody") && e.contains("not a handle"));
    }

    @Test
    void aParentMayWaitForASignalOnlyFromAHandleItStarted() {
        assertThat(errors(main("wait nobody signal \"x\" timeout 1m -> t"))).anyMatch(e -> e.contains("nobody") && e.contains("not a handle"));
        assertThat(errors(main("wait work signal \"x\" timeout 1m -> t"))).noneMatch(e -> e.contains("not a handle"));
    }

    @Test
    void aSuperviseSignalRuleNeedsAHandleToo() {
        assertThat(errors(main("supervise nobody { when signal \"x\" { wrap_up } }"))).anyMatch(e -> e.contains("nobody"));
    }

    // ---- names and payloads that can never be sent ----

    @Test
    void aSignalNameIsAPlainWordOfAtMostFortyCharacters() {
        assertThat(errors(main("notify work \"has space\" \"x\""))).anyMatch(e -> e.contains("plain word"));
        assertThat(errors(main("notify work \"" + "n".repeat(41) + "\" \"x\""))).anyMatch(e -> e.contains("40"));
        assertThat(errors(main("wait work signal \"bad-name\" timeout 1m -> t"))).anyMatch(e -> e.contains("plain word"));
        assertThat(errors(main("supervise work { when signal \"a b\" { wrap_up } }"))).anyMatch(e -> e.contains("plain word"));
        assertThat(errors(main("notify work \"" + "n".repeat(40) + "\" \"x\""))).noneMatch(e -> e.contains("40"));
    }

    @Test
    void aLiteralPayloadOverTwoThousandCharactersIsRefusedBeforeARun() {
        assertThat(errors(main("notify work \"go\" \"" + "x".repeat(2001) + "\""))).anyMatch(e -> e.contains("2000"));
        assertThat(errors(main("notify work \"go\" \"" + "x".repeat(2000) + "\""))).noneMatch(e -> e.contains("2000"));
    }

    @Test
    void aWaitForASignalNeedsATimeoutSoNothingWaitsForEver() {
        assertThat(errors(main("wait work signal \"x\" -> t"))).anyMatch(e -> e.contains("timeout"));
        assertThat(errors(check("workflow Solo(q) { wait signal \"x\" -> t }"))).anyMatch(e -> e.contains("timeout"));
    }

    // ---- G32: two waits for one name that can be active at once ----

    @Test
    void twoWaitsForTheSameNameInDifferentBranchesOfOneParallelAreRefused() {
        var p = check("workflow Solo(q) { parallel { wait signal \"go\" timeout 1m -> a  wait signal \"go\" timeout 1m -> b } }");

        assertThat(errors(p)).anyMatch(e -> e.contains("\"go\"") && e.contains("parallel"));
    }

    @Test
    void theSameNameInSequenceInALoopOrInDifferentNamesIsFine() {
        assertThat(errors(check("workflow Solo(q) { wait signal \"go\" timeout 1m -> a  wait signal \"go\" timeout 1m -> b }"))).noneMatch(e -> e.contains("parallel"));
        assertThat(errors(check("workflow Solo(q) { parallel { wait signal \"go\" timeout 1m -> a  wait signal \"stop\" timeout 1m -> b } }"))).noneMatch(e -> e.contains("parallel"));
        assertThat(errors(check("workflow Solo(q) { loop until (q == \"x\") max 3 { wait signal \"go\" timeout 1m -> a } }"))).noneMatch(e -> e.contains("parallel"));
    }

    @Test
    void aWaitForASignalInsideAParallelForEachIsRefusedBecauseEveryItemWouldWaitForTheSameOne() {
        var p = check("workflow Solo(q) { parallel for each t in q.items max 3 { wait signal \"go\" timeout 1m -> a } }");

        assertThat(errors(p)).anyMatch(e -> e.contains("\"go\"") && e.contains("parallel"));
    }

    // ---- G23: a signal's payload never becomes a steer's text ----

    @Test
    void aSteerWhoseTextIsBuiltFromASignalsPayloadIsALoadError() {
        assertThatThrownBy(() -> main("wait work signal \"stuck\" timeout 1m -> got\nsteer work Researcher \"{got}\""))
                .hasMessageContaining("steer")
                .hasMessageContaining("literal");
        assertThatThrownBy(() -> main("supervise work { when signal \"stuck\" { steer \"{stuck}\" } }"))
                .hasMessageContaining("literal");
    }

    @Test
    void aSuperviseRuleActionsTextStaysFixedWhateverTheSignalSays() {
        assertThat(errors(main("supervise work { when signal \"stuck\" { steer \"fixed words\" } }"))).noneMatch(e -> e.contains("steer"));
    }

    // ---- warnings ----

    @Test
    void aWorkflowThatSignalsItsParentButIsNeverStartedByOneIsWarnedAbout() {
        var never = check("workflow Solo(q) { notify parent \"hi\" \"x\" }\nworkflow Main(q) { note \"x\" }");
        var started = check("workflow Up(q) { notify parent \"hi\" \"x\" }\nworkflow Main(q) { start Up(q = q) budget { tokens: 1 } -> u\nwait u timeout 1m }");

        assertThat(warnings(never)).anyMatch(w -> w.contains("parent") && w.contains("nobody"));
        assertThat(warnings(started)).noneMatch(w -> w.contains("nobody"));
    }

    @Test
    void aChildWaitForASignalInAWorkflowNothingStartsIsWarnedAboutBecauseItFailsWhenRunDirectly() {
        var never = check("workflow Solo(q) { wait signal \"go\" timeout 1m -> a }\nworkflow Main(q) { note \"x\" }");

        assertThat(warnings(never)).anyMatch(w -> w.contains("parent") && w.contains("go"));
    }

    @Test
    void aWellFormedParentAndChildHaveNoErrorsAtAll() {
        var p =
                check(
                        """
                        workflow Worker(x) { wait signal "go" timeout 5m -> text  notify parent "done" "ok"  return text }
                        workflow Main(q) {
                            start Worker(x = q) budget { tokens: 10 } -> w
                            notify w "go" "now"
                            supervise w { when signal "stuck" { wrap_up } }
                            wait w signal "done" timeout 5m -> ok
                            wait w timeout 1m
                        }
                        """);

        assertThat(errors(p)).isEmpty();
    }
}
