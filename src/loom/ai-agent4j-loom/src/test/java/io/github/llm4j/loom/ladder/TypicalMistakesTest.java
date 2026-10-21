package io.github.llm4j.loom.ladder;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The mistakes people make when they write a ladder, each introduced into a good one: every one
 * must be caught before the agent runs, name the line, and say what was expected or what was
 * probably meant.
 */
@Tag("L2")
class TypicalMistakesTest {

    static final String GOOD =
            """
            agent Triager { model: "m" system: "s" output_schema: { choice: string, reasoning: string } }
            measure agrees(c) = c.response.choice == c.proposal.choice
            ladder L(baseline) {
                rung watch { mode: blind }
                rung suggest {
                    mode: confirm
                    climb when history.count >= 10 and history.rate(agrees).lower95 >= 0.8
                    slip when history.last(5).rate(agrees) < 0.4
                }
                rung act {
                    mode: autonomous
                    climb when history.count >= 30 and history.mean(amount) <= baseline
                    slip to suggest when history.last(10).rate(agrees) < 0.5
                    sample 5% blind
                    escalate when case.amount > 200
                }
                climbing needs approval from boss
            }
            decision D {
                proposed by: Triager
                choices: a, b, escalate
                remember: amount: number, tier: text
                ask: lead
                ladder: L(baseline: 100)
                ceiling: act
                when the agent changes: start over
            }
            workflow W(amount, tier) { decide D -> v note "{v}" }
            """;

    static Stream<Arguments> mistakes() {
        return Stream.of(
                Arguments.of(
                        "a misspelt field of the case",
                        "case.amount > 200",
                        "case.amout > 200",
                        "a case has no field 'amout'",
                        "did you mean 'amount'?"),
                Arguments.of(
                        "a misspelt aggregate",
                        "history.rate(agrees).lower95 >= 0.8",
                        "history.ratee(agrees).lower95 >= 0.8",
                        "no method 'ratee'",
                        "did you mean 'rate'?"),
                Arguments.of(
                        "a misspelt figure of a ratio",
                        "history.rate(agrees).lower95 >= 0.8",
                        "history.rate(agrees).lower >= 0.8",
                        "has no field 'lower'",
                        "did you mean 'lower95'?"),
                Arguments.of(
                        "a misspelt measure",
                        "history.rate(agrees).lower95 >= 0.8",
                        "history.rate(agree).lower95 >= 0.8",
                        "unknown name 'agree'",
                        "did you mean 'agrees'?"),
                Arguments.of(
                        "a text where a number is wanted",
                        "history.count >= 10 and",
                        "history.count >= \"ten\" and",
                        "cannot compare a number with a text",
                        null),
                Arguments.of(
                        "a number where a yes/no is wanted",
                        "history.rate(agrees).lower95 >= 0.8",
                        "history.rate(amount).lower95 >= 0.8",
                        "'rate' expects a yes/no condition",
                        null),
                Arguments.of(
                        "a duration compared with a number",
                        "history.count >= 30 and",
                        "history.span >= 30 and",
                        "cannot compare a duration with a number",
                        null),
                Arguments.of(
                        "a rung that slips up",
                        "slip to suggest when",
                        "slip to act when",
                        "must go down",
                        null),
                Arguments.of(
                        "an unknown rung to slip to",
                        "slip to suggest when",
                        "slip to sugest when",
                        "no rung of that name",
                        "did you mean 'suggest'?"),
                Arguments.of(
                        "an unknown mode",
                        "mode: confirm",
                        "mode: confirms",
                        "mode must be blind, confirm or autonomous",
                        null),
                Arguments.of(
                        "a climb on the first rung",
                        "rung watch { mode: blind }",
                        "rung watch { mode: blind climb when true }",
                        "first rung",
                        "climb"),
                Arguments.of(
                        "a rung that can never be reached",
                        "climb when history.count >= 30 and history.mean(amount) <= baseline",
                        "",
                        "can never be reached",
                        null),
                Arguments.of(
                        "a single equals sign",
                        "history.count >= 10 and",
                        "history.count = 10 and",
                        "write '=='",
                        null),
                Arguments.of(
                        "a missing bracket",
                        "history.last(5).rate(agrees) < 0.4",
                        "history.last(5.rate(agrees) < 0.4",
                        "expected ')'",
                        null),
                Arguments.of(
                        "no one to approve climbing",
                        "climbing needs approval from boss",
                        "",
                        "say who approves climbing",
                        null),
                Arguments.of(
                        "a ceiling that is not a rung",
                        "ceiling: act",
                        "ceiling: acting",
                        "no rung named acting",
                        "did you mean 'act'?"),
                Arguments.of(
                        "a ladder parameter nobody supplies",
                        "ladder: L(baseline: 100)",
                        "ladder: L",
                        "needs a value for baseline",
                        null),
                Arguments.of(
                        "a measure that calls itself",
                        "measure agrees(c) = c.response.choice == c.proposal.choice",
                        "measure agrees(c) = agrees(c)",
                        "calls itself",
                        null),
                Arguments.of(
                        "a measure that reads the history",
                        "measure agrees(c) = c.response.choice == c.proposal.choice",
                        "measure agrees(c) = history.count > 1",
                        "measures cannot read 'history'",
                        null),
                Arguments.of(
                        "two rungs of one name",
                        "rung act {",
                        "rung suggest {",
                        "declared twice",
                        null),
                Arguments.of(
                        "a decision with its own rules as well",
                        "ceiling: act",
                        "ceiling: act\n trust { moving up is automatic }",
                        "trust",
                        "ladder"));
    }

    private static int lineOf(String text, String needle) {
        int at = text.indexOf(needle);
        return at < 0
                ? -1
                : (int) text.substring(0, at).chars().filter(ch -> ch == '\n').count() + 1;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mistakes")
    void isCaughtBeforeTheAgentRunsNamesTheLineAndSaysWhatWasMeant(
            String what, String from, String to, String message, String hint) {
        assertThat(GOOD).contains(from);
        String bad = GOOD.replace(from, to);
        LoomScript script;
        try {
            script = new LoomParser(new Lexer(bad).tokenize()).parseScript();
        } catch (RuntimeException parseError) {
            // a mistake the lexer or parser stops on
            assertThat(parseError.getMessage())
                    .as(what)
                    .containsPattern("line \\d+")
                    .contains(message);
            if (hint != null) assertThat(parseError.getMessage()).as(what).contains(hint);
            return;
        }
        List<ScriptValidator.Problem> errors =
                new ScriptValidator()
                                .validate(
                                        script,
                                        new ScriptValidator.Context().registeredTools(Set.of()))
                                .stream()
                                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                                .toList();
        assertThat(errors).as(what).isNotEmpty();
        String found =
                errors.stream()
                        .map(ScriptValidator.Problem::toString)
                        .reduce("", (a, b) -> a + "\n" + b);
        assertThat(found).as(what).contains(message).containsPattern("line \\d+:");
        if (hint != null) assertThat(found).as(what).contains(hint);
    }

    @Test
    void theGoodLadderIsClean() {
        LoomScript script = new LoomParser(new Lexer(GOOD).tokenize()).parseScript();
        assertThat(
                        new ScriptValidator()
                                .validate(
                                        script,
                                        new ScriptValidator.Context().registeredTools(Set.of())))
                .isEmpty();
    }

    @Test
    void theLineInAMessagePointsAtTheMistake() {
        String bad = GOOD.replace("case.amount > 200", "case.amout > 200");
        LoomScript script = new LoomParser(new Lexer(bad).tokenize()).parseScript();
        String errors =
                new ScriptValidator()
                                .validate(
                                        script,
                                        new ScriptValidator.Context().registeredTools(Set.of()))
                                .stream()
                                .map(Object::toString)
                                .reduce("", (a, b) -> a + b);
        assertThat(errors).contains("line " + lineOf(bad, "case.amout"));
    }
}
