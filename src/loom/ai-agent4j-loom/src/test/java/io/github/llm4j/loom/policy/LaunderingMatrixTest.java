package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 26c, R6 to R8: every route a model-written value can take to the end of the workflow. Each
 * route is tried unchecked (it must be stopped) and, where there is a check, checked (it must pass).
 * The canary is in every model answer, so a route that let it out unchecked is a leak.
 */
class LaunderingMatrixTest {

    @TempDir Path dir;

    /** name, the statements between the delegate and the exit, what the workflow returns. */
    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("direct", "", "reply"),
                Arguments.of("set copy", "set copy = reply", "copy"),
                Arguments.of("set twice", "set a = reply\n  set b = a", "b"),
                Arguments.of("interpolation", "set note = \"Dear customer, {reply}\"", "note"),
                Arguments.of("object field", "set box = { text: reply }", "box"),
                Arguments.of("returned object", "", "{ text: reply }"),
                Arguments.of("extractor text field", "extract Claim from reply -> claim", "claim.summary"),
                Arguments.of("extractor whole record", "extract Claim from reply -> claim", "claim"),
                Arguments.of(
                        "call argument and result",
                        "call Echo(msg = reply) -> echoed",
                        "echoed"),
                Arguments.of(
                        "for each item",
                        "extract Claim from reply -> claim\n  for each p in claim.points { set seen = p }",
                        "seen"));
    }

    String script(String between, String ret, String check) {
        String body = "  delegate \"{q}\" to Writer -> reply\n  " + check + "\n  " + between + "\n  return " + ret + "\n";
        return SinkFixture.HEAD
                + "workflow Echo(msg) { return msg }\n"
                + "workflow Main(q) {\n"
                + body
                + "}\n";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void anUncheckedModelValueIsStoppedByEveryRoute(String name, String between, String ret) throws Exception {
        var o = SinkFixture.run(dir, script(between, ret, ""), "Main", Map.of("q", "hello"));
        assertThat(o.error()).as(name).isNotNull();
        assertThat(o.blocked()).as(name + " traced sink_blocked").isTrue();
        assertThat(o.message()).contains("has not been checked against HouseRules");
        assertThat(o.message()).doesNotContain(SinkFixture.CANARY);
    }

    /** Routes that copy or rebuild text without a model writing it again. */
    static Stream<Arguments> copyingRoutes() {
        return routes().filter(a -> !String.valueOf(a.get()[1]).contains("extract"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("copyingRoutes")
    void aCheckedValueGoesThroughEveryRouteThatOnlyCopiesIt(String name, String between, String ret)
            throws Exception {
        var o =
                SinkFixture.run(
                        dir,
                        script(between, ret, "check reply against HouseRules using Compliance"),
                        "Main",
                        Map.of("q", "hello"));
        assertThat(o.error()).as(name).isNull();
        assertThat(String.valueOf(o.result())).as(name).contains(SinkFixture.CANARY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void aModelThatWritesTheTextAgainMakesItUncheckedAgain(String name, String between, String ret)
            throws Exception {
        if (!between.contains("extract")) return; // only the routes through an extractor
        var o =
                SinkFixture.run(
                        dir,
                        script(between, ret, "check reply against HouseRules using Compliance"),
                        "Main",
                        Map.of("q", "hello"));
        assertThat(o.blocked()).as("the extractor's text is new model text: " + name).isTrue();
    }

    @org.junit.jupiter.api.Test
    void anExtractedTextFieldPassesOnceItIsCheckedItselfAndTheOtherFieldsNeverNeededIt() throws Exception {
        String checked =
                script(
                        "extract Claim from reply -> claim\n  check claim.summary against HouseRules using Compliance",
                        "claim.summary",
                        "");
        assertThat(SinkFixture.run(dir, checked, "Main", Map.of("q", "hello")).error()).isNull();
        for (String field : new String[] {"claim.amount", "claim.kind"}) {
            var o = SinkFixture.run(dir, script("extract Claim from reply -> claim", field, ""), "Main", Map.of("q", "hello"));
            assertThat(o.error()).as(field + " is not model text").isNull();
        }
    }

    @org.junit.jupiter.api.Test
    void whatIsNotModelWrittenGoesThroughWithNoCheck() throws Exception {
        String s =
                SinkFixture.HEAD
                        + "workflow Main(q) {\n  set fixed = \"We are here to help.\"\n  set both = \"{fixed} You asked: {q}\"\n  return both\n}\n";
        var o = SinkFixture.run(dir, s, "Main", Map.of("q", "hello"));
        assertThat(o.error()).isNull();
        assertThat(String.valueOf(o.result())).isEqualTo("We are here to help. You asked: hello");
    }

    @org.junit.jupiter.api.Test
    void noPolicyMeansNoEnforcement() throws Exception {
        String s =
                "agent Writer { model: \"writer\" system: \"You write replies.\" }\n"
                        + "workflow Main(q) { delegate \"{q}\" to Writer -> reply\n  return reply }\n";
        var o = SinkFixture.run(dir, s, "Main", Map.of("q", "hello"));
        assertThat(o.error()).isNull();
        assertThat(String.valueOf(o.result())).contains(SinkFixture.CANARY);
    }

    @org.junit.jupiter.api.Test
    void aCalledWorkflowsReturnIsNotAnExitButItsCallersIs() throws Exception {
        String s =
                SinkFixture.HEAD
                        + "workflow Sub(q) { delegate \"{q}\" to Writer -> inner\n  return inner }\n"
                        + "workflow Main(q) { call Sub(q = q) -> got\n  note \"in caller\"\n  return got }\n";
        var o = SinkFixture.run(dir, s, "Main", Map.of("q", "hello"));
        assertThat(o.events().stream().anyMatch(e -> "in caller".equals(e.text()))).as("Sub's return did not stop the run").isTrue();
        assertThat(o.blocked()).as("Main's return did").isTrue();
    }

    @org.junit.jupiter.api.Test
    void aValueMustPassEveryDeclaredPolicyAndTheErrorNamesTheOnesMissing() throws Exception {
        String s =
                SinkFixture.HEAD
                        + "policy Legal: \"legal\" checked by Compliance\n"
                        + "workflow Main(q) {\n  delegate \"{q}\" to Writer -> reply\n  check reply against HouseRules using Compliance\n  return reply\n}\n";
        var o = SinkFixture.run(dir, s, "Main", Map.of("q", "hello"));
        assertThat(o.blocked()).isTrue();
        assertThat(o.message()).contains("Legal").doesNotContain("HouseRules.");
        String both = s.replace("check reply against HouseRules using", "check reply against HouseRules, Legal using");
        assertThat(SinkFixture.run(dir, both, "Main", Map.of("q", "hello")).error()).isNull();
    }

    @org.junit.jupiter.api.Test
    void editingACheckedValueWithNewModelTextUnchecksIt() throws Exception {
        String s =
                SinkFixture.HEAD
                        + "workflow Main(q) {\n  delegate \"{q}\" to Writer -> reply\n  check reply against HouseRules using Compliance\n"
                        + "  delegate \"more\" to Writer -> extra\n  set both = \"{reply} {extra}\"\n  return both\n}\n";
        assertThat(SinkFixture.run(dir, s, "Main", Map.of("q", "hello")).blocked()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void theBlockedEventNamesTheVariableAndThePoliciesNeverTheText() throws Exception {
        var o = SinkFixture.run(dir, script("", "reply", ""), "Main", Map.of("q", "hello"));
        var e = o.events().stream().filter(t -> t.type().equals(io.github.llm4j.loom.execution.TraceEvent.SINK_BLOCKED)).findFirst().orElseThrow();
        String dump = String.valueOf(e.data()) + e.text();
        assertThat(dump).contains("reply").contains("HouseRules").contains("return").doesNotContain(SinkFixture.CANARY);
    }

    @org.junit.jupiter.api.Test
    void onFailureOfNoStepExistsForReturnSoTheRunFailsWithTheMessage() throws Exception {
        var o = SinkFixture.run(dir, script("", "reply", ""), "Main", Map.of("q", "hello"));
        assertThat(o.message()).contains("Add `check reply against HouseRules");
    }

    @org.junit.jupiter.api.Test
    void aResumedRunKeepsTheOriginOfWhatItReplays() throws Exception {
        var first = SinkFixture.run(dir, script("", "reply", "check reply against HouseRules using Compliance"), "Main", Map.of("q", "hello"));
        assertThat(first.error()).isNull();
        // Second run over the same journal: delegate and check are replayed, the return still passes.
        var again = SinkFixture.run(dir, script("", "reply", "check reply against HouseRules using Compliance"), "Main", Map.of("q", "hello"), null, first.journal(), null);
        assertThat(again.error()).isNull();
        assertThat(again.requests()).as("nothing was asked again").isEmpty();
        var unchecked = SinkFixture.run(dir, script("", "reply", ""), "Main", Map.of("q", "hello"));
        var replayedUnchecked = SinkFixture.run(dir, script("", "reply", ""), "Main", Map.of("q", "hello"), null, unchecked.journal(), null);
        assertThat(replayedUnchecked.blocked()).as("replaying does not launder").isTrue();
    }
}
