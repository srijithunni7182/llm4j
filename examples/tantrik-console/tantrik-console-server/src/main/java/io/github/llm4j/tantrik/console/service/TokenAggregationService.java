package io.github.llm4j.tantrik.console.service;

import io.github.llm4j.tantrik.console.model.AgentTokenStat;
import io.github.llm4j.tantrik.console.model.RunEvent;
import io.github.llm4j.tantrik.console.model.RunSummary;
import io.github.llm4j.tantrik.console.model.TokenBreakdown;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Aggregates token consumption data from {@link RunSummary} trace events.
 *
 * <p>Filters {@code TRACE_PRE_TURN} events, parses the agent name from the
 * {@code message} field (format: {@code "Agent <name> phase <phase>"}), and
 * groups per-agent statistics including input token estimates, squeezed turn
 * counts, and average compression ratios.
 */
@Service
public class TokenAggregationService {

    private final TantrikRunService tantrikRunService;

    public TokenAggregationService(TantrikRunService tantrikRunService) {
        this.tantrikRunService = tantrikRunService;
    }

    /**
     * Fetches the run by {@code runId} and delegates to {@link #aggregate(RunSummary)}.
     *
     * @param runId the run identifier
     * @return aggregated {@link TokenBreakdown} for the run
     * @throws NoSuchElementException if no run with the given ID exists
     */
    public TokenBreakdown aggregate(String runId) {
        RunSummary summary = tantrikRunService.getRun(runId);
        if (summary == null) {
            throw new NoSuchElementException("Run not found: " + runId);
        }
        return aggregate(summary);
    }

    /**
     * Aggregates token data from the given {@link RunSummary}.
     *
     * <p>Only {@code TRACE_PRE_TURN} events are considered. For each such event:
     * <ul>
     *   <li>The agent name is parsed from the {@code message} field.</li>
     *   <li>{@code inputTokensEstimate} is summed per agent.</li>
     *   <li>{@code squeezedCount} is incremented when {@code squeezed == true}.</li>
     *   <li>{@code avgCompressionRatio} is the mean of {@code compressionRatio} values
     *       for squeezed turns only (0.0 if no squeezed turns).</li>
     * </ul>
     *
     * @param summary the run summary to aggregate
     * @return a {@link TokenBreakdown} with per-agent stats and total token count
     */
    public TokenBreakdown aggregate(RunSummary summary) {
        // Accumulator per agent: [inputTokens, squeezedCount, compressionRatioSum, squeezedTurnCount]
        Map<String, long[]> agentAccumulators = new LinkedHashMap<>();

        for (RunEvent event : summary.getEvents()) {
            if (!"TRACE_PRE_TURN".equals(event.type())) {
                continue;
            }

            String agentName = parseAgentName(event.message());
            if (agentName == null) {
                continue;
            }

            Map<String, Object> meta = event.metadata();
            int inputTokens = toInt(meta.get("inputTokensEstimate"));
            boolean squeezed = toBoolean(meta.get("squeezed"));
            double compressionRatio = toDouble(meta.get("compressionRatio"));

            long[] acc = agentAccumulators.computeIfAbsent(agentName, k -> new long[4]);
            // acc[0] = inputTokens sum (stored as long, cast back to int at the end)
            // acc[1] = squeezedCount
            // acc[2] = compressionRatio sum * 1_000_000 (fixed-point to avoid double accumulation issues)
            // acc[3] = squeezed turn count (denominator for avg)
            acc[0] += inputTokens;
            if (squeezed) {
                acc[1]++;
                acc[2] += (long) (compressionRatio * 1_000_000);
                acc[3]++;
            }
        }

        List<AgentTokenStat> agentBreakdown = new ArrayList<>();
        int totalInputTokens = 0;

        for (Map.Entry<String, long[]> entry : agentAccumulators.entrySet()) {
            long[] acc = entry.getValue();
            int agentTokens = (int) acc[0];
            int squeezedCount = (int) acc[1];
            double avgCompressionRatio = acc[3] > 0
                    ? (acc[2] / 1_000_000.0) / acc[3]
                    : 0.0;

            agentBreakdown.add(new AgentTokenStat(
                    entry.getKey(),
                    agentTokens,
                    squeezedCount,
                    avgCompressionRatio
            ));
            totalInputTokens += agentTokens;
        }

        long runDurationMs = computeRunDurationMs(summary.getStartedAt(), summary.getCompletedAt());

        return new TokenBreakdown(totalInputTokens, agentBreakdown, runDurationMs);
    }

    /**
     * Parses the agent name from a message of the form {@code "Agent <name> phase <phase>"}.
     *
     * @param message the event message
     * @return the agent name, or {@code null} if the message does not match the expected format
     */
    private String parseAgentName(String message) {
        if (message == null) {
            return null;
        }
        // Expected format: "Agent <name> phase <phase>"
        int agentIdx = message.indexOf("Agent ");
        if (agentIdx < 0) {
            return null;
        }
        int nameStart = agentIdx + "Agent ".length();
        int phaseIdx = message.indexOf(" phase ", nameStart);
        if (phaseIdx < 0) {
            return null;
        }
        String name = message.substring(nameStart, phaseIdx).trim();
        return name.isEmpty() ? null : name;
    }

    private long computeRunDurationMs(Instant startedAt, Instant completedAt) {
        if (startedAt == null || completedAt == null) {
            return 0L;
        }
        return completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return false;
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}
