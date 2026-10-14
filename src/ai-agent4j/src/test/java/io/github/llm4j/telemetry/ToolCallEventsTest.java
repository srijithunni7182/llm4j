package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.AgentInterrupt;
import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.ToolException;
import java.net.SocketTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One event per tool call, in both loops, whatever happened to the call (design §4.2, R6 to R9). */
class ToolCallEventsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aToolThatSucceeds(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TelemetryListener l = new TelemetryListener();
        TestTool t = new TestTool("t", (a, n) -> {
            clock.advance(120);
            return "result text";
        });
        AgentResult r = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).clock(clock).addListener(l).build().run("Do it.");
        ToolCallEvent e = l.onlyTool();
        assertThat(e.tool()).isEqualTo("t");
        assertThat(e.outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(e.failed()).isFalse();
        assertThat(e.softFailure()).isFalse();
        assertThat(e.category()).isNull();
        assertThat(e.errorType()).isNull();
        assertThat(e.durationMillis()).isEqualTo(120);
        assertThat(e.approvalMillis()).isZero();
        assertThat(e.iteration()).isZero();
        assertThat(e.startedAt()).isEqualTo(java.time.Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(e.resultChars()).isEqualTo("result text".length());
        assertThat(e.argumentChars()).isPositive();
        assertThat(r.getSteps().get(0).getOutcome()).isEqualTo(AgentResult.StepOutcome.EXECUTED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void theEventHoldsNoResultOrArgumentText(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        TestTool t = TestTool.returning("t", "TOP-SECRET-RESULT");
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "SECRET-ARGUMENT")).addTool(t).addListener(l).build().run("Do it.");
        assertThat(l.onlyTool().toString()).doesNotContain("TOP-SECRET-RESULT").doesNotContain("SECRET-ARGUMENT");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aToolThatThrowsIsClassifiedByItsType(boolean nativeLoop) {
        Object[][] cases = {
            {new IllegalArgumentException("bad q"), ErrorCategory.INVALID_ARGUMENTS, "IllegalArgumentException"},
            {new SocketTimeoutException("Read timed out"), ErrorCategory.TIMEOUT, "SocketTimeoutException"},
            {new ToolException(ErrorCategory.NOT_FOUND, "no such record"), ErrorCategory.NOT_FOUND, "ToolException"},
            {new RuntimeException("wrapper", new SocketTimeoutException("slow")), ErrorCategory.TIMEOUT, "RuntimeException"},
            {new IllegalStateException("odd"), ErrorCategory.OTHER, "IllegalStateException"},
        };
        for (Object[] c : cases) {
            TelemetryListener l = new TelemetryListener();
            TestTool t = new TestTool("t", (a, n) -> {
                throw (Exception) c[0];
            });
            AgentResult r = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).addListener(l).build().run("Do it.");
            ToolCallEvent e = l.onlyTool();
            assertThat(e.outcome()).as(c[2].toString()).isEqualTo(ToolOutcome.EXECUTION_ERROR);
            assertThat(e.failed()).isTrue();
            assertThat(e.softFailure()).isFalse();
            assertThat(e.category()).isEqualTo(c[1]);
            assertThat(e.errorType()).isEqualTo(c[2]);
            assertThat(r.getSteps().get(0).getOutcome()).isEqualTo(AgentResult.StepOutcome.EXECUTION_ERROR);
            assertThat(r.getSteps().get(0).getObservation()).startsWith("Error executing tool:"); // what the model is told is unchanged
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aToolThatReturnsAnErrorFailedEvenThoughItWasExecuted(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        AgentResult r = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x"))
                .addTool(TestTool.returning("t", "Error: Endpoint 'GET /x' not found in API specification")).addListener(l).build().run("Do it.");
        ToolCallEvent e = l.onlyTool();
        assertThat(e.outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(e.softFailure()).isTrue();
        assertThat(e.failed()).isTrue();
        assertThat(e.category()).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(e.errorType()).isNull();
        assertThat(r.getSteps().get(0).getOutcome()).as("the agent's own step outcome is unchanged").isEqualTo(AgentResult.StepOutcome.EXECUTED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aToolCanChangeHowItsResultIsRead(boolean nativeLoop) {
        TestTool mine = TestTool.returning("t", "ERR 42: no record");
        mine.failure = s -> s.startsWith("ERR ");
        TelemetryListener l1 = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(mine).addListener(l1).build().run("Do it.");
        assertThat(l1.onlyTool().softFailure()).isTrue();

        TestTool other = TestTool.returning("t", "Error: this tool says errors are fine");
        other.failure = s -> false;
        TelemetryListener l2 = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(other).addListener(l2).build().run("Do it.");
        assertThat(l2.onlyTool().failed()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aBrokenIsFailureDoesNotBreakTheRun(boolean nativeLoop) {
        TestTool t = TestTool.returning("t", "fine");
        t.failure = s -> {
            throw new IllegalStateException("my own bug");
        };
        TelemetryListener l = new TelemetryListener();
        AgentResult r = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).addListener(l).build().run("Do it.");
        assertThat(r.getFinalAnswer()).isEqualTo("done");
        assertThat(l.onlyTool().failed()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aToolTheModelInventedIsUnknown(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "ghost", "x")).addTool(TestTool.returning("t", "x")).addListener(l).build().run("Do it.");
        ToolCallEvent e = l.onlyTool();
        assertThat(e.tool()).isEqualTo("ghost");
        assertThat(e.outcome()).isEqualTo(ToolOutcome.UNKNOWN_TOOL);
        assertThat(e.category()).isEqualTo(ErrorCategory.UNKNOWN_TOOL);
        assertThat(e.failed()).isTrue();
        assertThat(e.ran()).isFalse();
        assertThat(e.durationMillis()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void anIdenticalRepeatIsBlocked(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        TestTool t = TestTool.returning("t", "x");
        ReActAgent.builder().llmClient(Scripts.twoIdenticalToolSteps(nativeLoop, "t", "x")).addTool(t).addListener(l).build().run("Do it.");
        assertThat(l.tools).hasSize(2);
        assertThat(l.tools.get(0).outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(l.tools.get(0).iteration()).isZero();
        assertThat(l.tools.get(1).outcome()).isEqualTo(ToolOutcome.DUPLICATE_BLOCKED);
        assertThat(l.tools.get(1).category()).isEqualTo(ErrorCategory.REPEAT_BLOCKED);
        assertThat(l.tools.get(1).iteration()).isEqualTo(1);
        assertThat(t.calls).hasValue(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void anActionThatNeedsApprovalWithNoOneToAsk(boolean nativeLoop) {
        TestTool t = TestTool.returning("t", "x");
        t.approval = true;
        TelemetryListener l = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).addListener(l).build().run("Do it.");
        assertThat(l.onlyTool().outcome()).isEqualTo(ToolOutcome.APPROVAL_UNAVAILABLE);
        assertThat(l.onlyTool().category()).isEqualTo(ErrorCategory.APPROVAL_UNAVAILABLE);
        assertThat(t.calls).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aPersonRejectsTheAction(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TestTool t = TestTool.returning("t", "x");
        t.approval = true;
        TelemetryListener l = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).clock(clock).addListener(l)
                .approvalCallback((tool, args, thought) -> {
                    clock.advance(5_000);
                    return false;
                }).build().run("Do it.");
        ToolCallEvent e = l.onlyTool();
        assertThat(e.outcome()).isEqualTo(ToolOutcome.REJECTED_BY_HUMAN);
        assertThat(e.category()).isEqualTo(ErrorCategory.REJECTED_BY_HUMAN);
        assertThat(e.approvalMillis()).isEqualTo(5_000);
        assertThat(e.durationMillis()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aPersonApprovesAndTheWaitIsNotCountedAsTheToolsTime(boolean nativeLoop) {
        TestClock clock = new TestClock();
        TestTool t = new TestTool("t", (a, n) -> {
            clock.advance(100);
            return "done it";
        });
        t.approval = true;
        TelemetryListener l = new TelemetryListener();
        ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).clock(clock).addListener(l)
                .approvalCallback((tool, args, thought) -> {
                    clock.advance(3_000);
                    return true;
                }).build().run("Do it.");
        ToolCallEvent e = l.onlyTool();
        assertThat(e.outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(e.approvalMillis()).isEqualTo(3_000);
        assertThat(e.durationMillis()).isEqualTo(100);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aDeliberateInterruptStillPropagatesAndIsNotAToolFailure(boolean nativeLoop) {
        TestTool t = new TestTool("t", (a, n) -> {
            throw new AgentInterrupt("wait for a person");
        });
        TelemetryListener l = new TelemetryListener();
        ReActAgent agent = ReActAgent.builder().llmClient(Scripts.oneToolStep(nativeLoop, "t", "x")).addTool(t).addListener(l).build();
        assertThatThrownBy(() -> agent.run("Do it.")).isInstanceOf(AgentInterrupt.class);
        assertThat(l.tools).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void eachCallInARunGetsItsOwnEventInOrder(boolean nativeLoop) {
        TelemetryListener l = new TelemetryListener();
        TestTool t = new TestTool("t", (a, n) -> n == 2 ? "Error: not found" : "ok " + n);
        ReActAgent.builder().llmClient(Scripts.toolSteps(nativeLoop, "t", 3)).addTool(t).addListener(l).build().run("Do it.");
        assertThat(l.tools).hasSize(3);
        assertThat(l.tools).extracting(ToolCallEvent::iteration).containsExactly(0, 1, 2);
        assertThat(l.tools).extracting(ToolCallEvent::failed).containsExactly(false, true, false);
    }
}
