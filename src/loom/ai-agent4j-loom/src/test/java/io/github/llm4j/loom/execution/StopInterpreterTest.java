package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.StopReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The default table of the design: what is done with a stopped step when a script says nothing. */
class StopInterpreterTest {

    private static StopReport stop(StopReport.Kind kind, String reason, boolean changed) {
        return new StopReport(
                kind,
                reason,
                "someone",
                StopReport.Phase.BETWEEN_STEPS,
                "partial",
                List.of(),
                changed);
    }

    @ParameterizedTest(name = "{0}, changed={1} -> {2}")
    @CsvSource({
        "operator,false,ASK",
        "operator,true,ASK",
        "user,false,ASK",
        "shutdown,false,SUSPEND",
        "shutdown,true,SUSPEND",
        "sibling,true,DISCARD",
        "budget,true,USE_PARTIAL",
        "timeout,false,RETRY",
        "timeout,true,USE_PARTIAL",
        "safety,false,ASK",
        "something-new,false,ASK"
    })
    void aCancelFollowsTheTable(String reason, boolean changed, StopOutcome expected) {
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.CANCEL, reason, changed), null, true)
                                .outcome())
                .isEqualTo(expected);
    }

    @Test
    void aReasonlessCancelIsAskedAbout() {
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.CANCEL, null, false), null, true)
                                .outcome())
                .isEqualTo(StopOutcome.ASK);
    }

    @ParameterizedTest(name = "wrap-up for {0} -> {1}")
    @CsvSource({"budget,USE_PARTIAL", "deadline,USE_PARTIAL", "safety,ASK"})
    void aWrapUpKeepsItsAnswerExceptWhenItIsASafetyStop(String reason, StopOutcome expected) {
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.WRAP_UP, reason, true), null, true)
                                .outcome())
                .isEqualTo(expected);
    }

    @Test
    void whoeverStoppedItMayNameTheOutcome() {
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.CANCEL, "user", true),
                                        StopOutcome.SKIP,
                                        true)
                                .outcome())
                .isEqualTo(StopOutcome.SKIP);
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.CANCEL, "user", false),
                                        StopOutcome.CONCLUDE,
                                        true)
                                .outcome())
                .isEqualTo(StopOutcome.CONCLUDE);
    }

    @Test
    void aRetryIsRefusedWhenTheAgentMayAlreadyHaveChangedSomething() {
        StopInterpreter.Decision d =
                StopInterpreter.decide(
                        stop(StopReport.Kind.CANCEL, "user", true), StopOutcome.RETRY, true);
        assertThat(d.outcome()).isEqualTo(StopOutcome.ASK);
        assertThat(d.why()).contains("already have changed");
        assertThat(
                        StopInterpreter.decide(
                                        stop(StopReport.Kind.CANCEL, "user", false),
                                        StopOutcome.RETRY,
                                        true)
                                .outcome())
                .isEqualTo(StopOutcome.RETRY);
    }

    @Test
    void withNobodyToAskTheRunIsSuspendedNotGuessedAbout() {
        StopInterpreter.Decision d =
                StopInterpreter.decide(stop(StopReport.Kind.CANCEL, "user", false), null, false);
        assertThat(d.outcome()).isEqualTo(StopOutcome.SUSPEND);
        assertThat(d.why()).contains("nobody is set up to ask");
    }
}
