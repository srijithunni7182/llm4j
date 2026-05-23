package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.AgentTokenStat;
import io.github.llm4j.tantrik.console.model.RunEvent;
import io.github.llm4j.tantrik.console.model.RunSummary;
import io.github.llm4j.tantrik.console.model.TokenBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TokenAggregationService} aggregation logic.
 * Validates: Requirements 5.1
 */
@ExtendWith(MockitoExtension.class)
class TokenAggregationServiceTest {

    @Mock
    TantrikRunService tantrikRunService;

    TokenAggregationService service;

    @BeforeEach
    void setUp() {
        service = new TokenAggregationService(tantrikRunService);
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private static RunEvent preTurnEvent(String agentName, int inputTokens, boolean squeezed, double compressionRatio) {
        return new RunEvent(
                "TRACE_PRE_TURN",
                "Agent " + agentName + " phase PRE_TURN",
                "LOCAL",
                Instant.now(),
                Map.of(
                        "inputTokensEstimate", inputTokens,
                        "squeezed", squeezed,
                        "compressionRatio", compressionRatio
                )
        );
    }

    private static RunEvent otherEvent(String type) {
        return new RunEvent(type, "some message", "SYSTEM", Instant.now(), Map.of());
    }

    private static RunSummary summaryWith(List<RunEvent> events) {
        RunSummary summary = new RunSummary();
        summary.setRunId("run-1");
        summary.setEvents(events);
        return summary;
    }

    private static RunSummary summaryWith(List<RunEvent> events, Instant startedAt, Instant completedAt) {
        RunSummary summary = summaryWith(events);
        summary.setStartedAt(startedAt);
        summary.setCompletedAt(completedAt);
        return summary;
    }

    // -------------------------------------------------------------------------
    // 1. Per-agent grouping
    // -------------------------------------------------------------------------

    @Test
    void aggregate_groupsByAgentName() {
        // Two events for agentA (100 + 200 = 300 tokens), one for agentB (150 tokens)
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, false, 0.0),
                preTurnEvent("agentB", 150, false, 0.0),
                preTurnEvent("agentA", 200, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(450);
        assertThat(breakdown.agentBreakdown()).hasSize(2);

        AgentTokenStat agentA = findAgent(breakdown, "agentA");
        assertThat(agentA.inputTokens()).isEqualTo(300);

        AgentTokenStat agentB = findAgent(breakdown, "agentB");
        assertThat(agentB.inputTokens()).isEqualTo(150);
    }

    @Test
    void aggregate_preservesInsertionOrderOfAgents() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("first", 10, false, 0.0),
                preTurnEvent("second", 20, false, 0.0),
                preTurnEvent("third", 30, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        List<String> names = breakdown.agentBreakdown().stream()
                .map(AgentTokenStat::agentName)
                .toList();
        assertThat(names).containsExactly("first", "second", "third");
    }

    // -------------------------------------------------------------------------
    // 2. runDurationMs calculation
    // -------------------------------------------------------------------------

    @Test
    void aggregate_calculatesRunDurationMs() {
        Instant start = Instant.ofEpochMilli(1_000_000L);
        Instant end   = Instant.ofEpochMilli(1_005_000L); // 5 000 ms later

        RunSummary summary = summaryWith(List.of(), start, end);

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.runDurationMs()).isEqualTo(5_000L);
    }

    @Test
    void aggregate_returnsZeroDuration_whenCompletedAtIsNull() {
        Instant start = Instant.ofEpochMilli(1_000_000L);
        RunSummary summary = summaryWith(List.of(), start, null);

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.runDurationMs()).isEqualTo(0L);
    }

    @Test
    void aggregate_returnsZeroDuration_whenStartedAtIsNull() {
        Instant end = Instant.ofEpochMilli(1_005_000L);
        RunSummary summary = summaryWith(List.of(), null, end);

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.runDurationMs()).isEqualTo(0L);
    }

    @Test
    void aggregate_returnsZeroDuration_whenBothTimestampsAreNull() {
        RunSummary summary = summaryWith(List.of(), null, null);

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.runDurationMs()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // 3. Squeeze event counting and avgCompressionRatio
    // -------------------------------------------------------------------------

    @Test
    void aggregate_countsSqueezeEvents() {
        // agentA: 2 squeezed turns with ratios 0.5 and 0.75 → avg = 0.625
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, true,  0.5),
                preTurnEvent("agentA", 200, true,  0.75),
                preTurnEvent("agentA", 150, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        AgentTokenStat stat = findAgent(breakdown, "agentA");
        assertThat(stat.squeezedCount()).isEqualTo(2);
        assertThat(stat.avgCompressionRatio()).isCloseTo(0.625, within(1e-6));
    }

    @Test
    void aggregate_returnsZeroAvgCompressionRatio_whenNoSqueezedTurns() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        AgentTokenStat stat = findAgent(breakdown, "agentA");
        assertThat(stat.squeezedCount()).isEqualTo(0);
        assertThat(stat.avgCompressionRatio()).isEqualTo(0.0);
    }

    @Test
    void aggregate_countsSqueezeEvents_acrossMultipleAgents() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, true,  0.4),
                preTurnEvent("agentB", 200, false, 0.0),
                preTurnEvent("agentB", 300, true,  0.6)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        AgentTokenStat statA = findAgent(breakdown, "agentA");
        assertThat(statA.squeezedCount()).isEqualTo(1);
        assertThat(statA.avgCompressionRatio()).isCloseTo(0.4, within(1e-6));

        AgentTokenStat statB = findAgent(breakdown, "agentB");
        assertThat(statB.squeezedCount()).isEqualTo(1);
        assertThat(statB.avgCompressionRatio()).isCloseTo(0.6, within(1e-6));
    }

    // -------------------------------------------------------------------------
    // 4. Non-TRACE_PRE_TURN events are ignored
    // -------------------------------------------------------------------------

    @Test
    void aggregate_ignoresNonPreTurnEvents() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, false, 0.0),
                otherEvent("RUN_STARTED"),
                otherEvent("TRACE_POST_TURN"),
                otherEvent("RUN_COMPLETED")
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        // Only the one TRACE_PRE_TURN event should be counted
        assertThat(breakdown.totalInputTokens()).isEqualTo(100);
        assertThat(breakdown.agentBreakdown()).hasSize(1);
        assertThat(breakdown.agentBreakdown().get(0).agentName()).isEqualTo("agentA");
    }

    @Test
    void aggregate_returnsEmptyBreakdown_whenNoPreTurnEvents() {
        RunSummary summary = summaryWith(List.of(
                otherEvent("RUN_STARTED"),
                otherEvent("RUN_COMPLETED")
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(0);
        assertThat(breakdown.agentBreakdown()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 5. aggregate(String runId) — delegates to TantrikRunService
    // -------------------------------------------------------------------------

    @Test
    void aggregate_byRunId_delegatesToRunService() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentX", 50, false, 0.0)
        ));
        when(tantrikRunService.getRun("run-42")).thenReturn(summary);

        TokenBreakdown breakdown = service.aggregate("run-42");

        assertThat(breakdown.totalInputTokens()).isEqualTo(50);
        verify(tantrikRunService).getRun("run-42");
    }

    @Test
    void aggregate_byRunId_throwsNoSuchElementException_whenRunNotFound() {
        when(tantrikRunService.getRun("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.aggregate("missing"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("missing");
    }

    // -------------------------------------------------------------------------
    // 6. Edge cases
    // -------------------------------------------------------------------------

    @Test
    void aggregate_handlesEmptyEventList() {
        RunSummary summary = summaryWith(List.of());

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(0);
        assertThat(breakdown.agentBreakdown()).isEmpty();
        assertThat(breakdown.runDurationMs()).isEqualTo(0L);
    }

    @Test
    void aggregate_ignoresPreTurnEvent_withMalformedMessage() {
        // Message does not match "Agent <name> phase <phase>" format
        RunEvent badEvent = new RunEvent(
                "TRACE_PRE_TURN",
                "malformed message without agent info",
                "LOCAL",
                Instant.now(),
                Map.of("inputTokensEstimate", 999, "squeezed", false, "compressionRatio", 0.0)
        );
        RunSummary summary = summaryWith(List.of(badEvent));

        TokenBreakdown breakdown = service.aggregate(summary);

        // Malformed message → agent name is null → event is skipped
        assertThat(breakdown.totalInputTokens()).isEqualTo(0);
        assertThat(breakdown.agentBreakdown()).isEmpty();
    }

    @Test
    void aggregate_ignoresPreTurnEvent_withNullMessage() {
        RunEvent nullMsgEvent = new RunEvent(
                "TRACE_PRE_TURN",
                null,
                "LOCAL",
                Instant.now(),
                Map.of("inputTokensEstimate", 500, "squeezed", false, "compressionRatio", 0.0)
        );
        RunSummary summary = summaryWith(List.of(nullMsgEvent));

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(0);
        assertThat(breakdown.agentBreakdown()).isEmpty();
    }

    @Test
    void aggregate_handlesZeroInputTokens() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 0, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(0);
        assertThat(breakdown.agentBreakdown()).hasSize(1);
        assertThat(breakdown.agentBreakdown().get(0).inputTokens()).isEqualTo(0);
    }

    @Test
    void aggregate_sumsTotalInputTokensAcrossAllAgents() {
        RunSummary summary = summaryWith(List.of(
                preTurnEvent("agentA", 100, false, 0.0),
                preTurnEvent("agentB", 200, false, 0.0),
                preTurnEvent("agentC", 300, false, 0.0)
        ));

        TokenBreakdown breakdown = service.aggregate(summary);

        assertThat(breakdown.totalInputTokens()).isEqualTo(600);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private AgentTokenStat findAgent(TokenBreakdown breakdown, String agentName) {
        return breakdown.agentBreakdown().stream()
                .filter(s -> agentName.equals(s.agentName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Agent not found: " + agentName));
    }
}
