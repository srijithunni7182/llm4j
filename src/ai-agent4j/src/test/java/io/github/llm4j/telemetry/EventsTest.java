package io.github.llm4j.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.LLMResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventsTest {

    @Test
    void aModelCallEventWorksOutWhatFollowsFromItsFields() {
        ModelCallEvent e = ModelCallEvent.builder().iteration(3).requestedModel("gemini").model("gemini-2.5-flash").startedAt(Instant.EPOCH)
                .durationMillis(1_200).waitedMillis(400).attempts(3).failovers(List.of("primary")).promptTokens(900).completionTokens(100)
                .totalTokens(1_000).cost(new BigDecimal("0.002")).finishReason(LLMResponse.FinishReason.LENGTH).toolCallsRequested(2).build();
        assertThat(e.processingMillis()).isEqualTo(800);
        assertThat(e.truncated()).isTrue();
        assertThat(e.failed()).isFalse();
        assertThat(e.outcome()).isEqualTo(ModelCallEvent.Outcome.OK);
        assertThat(e.retries()).isEqualTo(2);
        assertThat(e.failovers()).containsExactly("primary");
    }

    @Test
    void aFinishThatIsNotLengthIsNotTruncated() {
        for (LLMResponse.FinishReason r : LLMResponse.FinishReason.values()) {
            ModelCallEvent e = ModelCallEvent.builder().finishReason(r).build();
            assertThat(e.truncated()).as(r.name()).isEqualTo(r == LLMResponse.FinishReason.LENGTH);
        }
        assertThat(ModelCallEvent.builder().build().truncated()).isFalse(); // no response, no finish reason
    }

    @Test
    void aFailedCallHasACategoryAndAType() {
        ModelCallEvent e = ModelCallEvent.builder().failed(ErrorCategory.RATE_LIMITED, "RateLimitException").build();
        assertThat(e.failed()).isTrue();
        assertThat(e.outcome()).isEqualTo(ModelCallEvent.Outcome.FAILED);
        assertThat(e.category()).isEqualTo(ErrorCategory.RATE_LIMITED);
        assertThat(e.errorType()).isEqualTo("RateLimitException");
    }

    @Test
    void theEventsAreImmutable() {
        List<String> fo = new java.util.ArrayList<>(List.of("a"));
        ModelCallEvent e = ModelCallEvent.builder().failovers(fo).build();
        fo.add("b");
        assertThat(e.failovers()).containsExactly("a");
        assertThatThrownBy(() -> e.failovers().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theNumbersMustMakeSense() {
        assertThatThrownBy(() -> ModelCallEvent.builder().attempts(0).build()).hasMessageContaining("attempts");
        assertThatThrownBy(() -> ModelCallEvent.builder().durationMillis(-1).build()).hasMessageContaining("durationMillis");
        assertThatThrownBy(() -> ModelCallEvent.builder().waitedMillis(-1).build()).hasMessageContaining("waitedMillis");
        assertThatThrownBy(() -> ModelCallEvent.builder().durationMillis(100).waitedMillis(200).build()).hasMessageContaining("waitedMillis");
        assertThatThrownBy(() -> ToolCallEvent.builder().tool(null).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolCallEvent.builder().tool("t").durationMillis(-5).build()).hasMessageContaining("durationMillis");
    }

    @Test
    void aToolCallThatRanAndSucceeded() {
        ToolCallEvent e = ToolCallEvent.builder().iteration(1).tool("search").startedAt(Instant.EPOCH).durationMillis(80).argumentChars(20).resultChars(900).build();
        assertThat(e.outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(e.failed()).isFalse();
        assertThat(e.softFailure()).isFalse();
        assertThat(e.ran()).isTrue();
    }

    @Test
    void aToolThatReturnedAnErrorFailedEvenThoughItWasExecuted() {
        ToolCallEvent e = ToolCallEvent.builder().tool("search").softFailure(ErrorCategory.NOT_FOUND).build();
        assertThat(e.outcome()).isEqualTo(ToolOutcome.EXECUTED);
        assertThat(e.softFailure()).isTrue();
        assertThat(e.failed()).isTrue();
        assertThat(e.category()).isEqualTo(ErrorCategory.NOT_FOUND);
        assertThat(e.errorType()).isNull();
    }

    @Test
    void everyOutcomeButExecutedIsAFailure() {
        for (ToolOutcome o : ToolOutcome.values()) {
            ToolCallEvent e = ToolCallEvent.builder().tool("t").outcome(o, ErrorCategory.OTHER, null).build();
            assertThat(e.failed()).as(o.name()).isEqualTo(o != ToolOutcome.EXECUTED);
        }
    }

    @Test
    void onlyCallsThatReachedTheToolHaveALatency() {
        assertThat(ToolCallEvent.builder().tool("t").outcome(ToolOutcome.EXECUTED, null, null).build().ran()).isTrue();
        assertThat(ToolCallEvent.builder().tool("t").outcome(ToolOutcome.EXECUTION_ERROR, ErrorCategory.TIMEOUT, "SocketTimeoutException").build().ran()).isTrue();
        for (ToolOutcome o : new ToolOutcome[] {ToolOutcome.UNKNOWN_TOOL, ToolOutcome.DUPLICATE_BLOCKED, ToolOutcome.APPROVAL_UNAVAILABLE, ToolOutcome.REJECTED_BY_HUMAN, ToolOutcome.BUDGET_EXHAUSTED}) {
            assertThat(ToolCallEvent.builder().tool("t").outcome(o, ErrorCategory.OTHER, null).build().ran()).as(o.name()).isFalse();
        }
    }

    @Test
    void eachOutcomeHasItsDefaultCategory() {
        assertThat(ToolOutcome.UNKNOWN_TOOL.defaultCategory()).isEqualTo(ErrorCategory.UNKNOWN_TOOL);
        assertThat(ToolOutcome.DUPLICATE_BLOCKED.defaultCategory()).isEqualTo(ErrorCategory.REPEAT_BLOCKED);
        assertThat(ToolOutcome.APPROVAL_UNAVAILABLE.defaultCategory()).isEqualTo(ErrorCategory.APPROVAL_UNAVAILABLE);
        assertThat(ToolOutcome.REJECTED_BY_HUMAN.defaultCategory()).isEqualTo(ErrorCategory.REJECTED_BY_HUMAN);
        assertThat(ToolOutcome.BUDGET_EXHAUSTED.defaultCategory()).isEqualTo(ErrorCategory.BUDGET);
        assertThat(ToolOutcome.EXECUTION_ERROR.defaultCategory()).isEqualTo(ErrorCategory.OTHER);
        assertThat(ToolOutcome.EXECUTED.defaultCategory()).isNull();
    }
}
