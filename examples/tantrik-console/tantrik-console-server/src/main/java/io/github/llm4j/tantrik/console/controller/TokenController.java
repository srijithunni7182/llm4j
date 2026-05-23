package io.github.llm4j.tantrik.console.controller;

import io.github.llm4j.tantrik.console.model.TokenBreakdown;
import io.github.llm4j.tantrik.console.service.TokenAggregationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for token consumption aggregation.
 *
 * <p>Error handling (404 for unknown run IDs) is delegated to
 * {@code GlobalExceptionHandler}, which catches {@link java.util.NoSuchElementException}
 * and returns HTTP 404 with an {@code {"error": "..."}} body.
 *
 * <p>Requirements: 5.1
 */
@RestController
@RequestMapping("/api/runs")
public class TokenController {

    private final TokenAggregationService tokenAggregationService;

    public TokenController(TokenAggregationService tokenAggregationService) {
        this.tokenAggregationService = tokenAggregationService;
    }

    /**
     * GET /api/runs/{runId}/tokens
     *
     * <p>Returns aggregated token consumption data for the specified run.
     * Iterates {@code TRACE_PRE_TURN} events in the run's event list, groups
     * by agent name, and returns per-agent input token counts, squeezed turn
     * counts, average compression ratios, and the total run duration.
     *
     * @param runId the run identifier
     * @return {@link TokenBreakdown} with total tokens, per-agent breakdown, and run duration
     * @throws java.util.NoSuchElementException (→ HTTP 404) if no run with the given ID exists
     */
    @GetMapping("/{runId}/tokens")
    public ResponseEntity<TokenBreakdown> getTokens(@PathVariable String runId) {
        TokenBreakdown breakdown = tokenAggregationService.aggregate(runId);
        return ResponseEntity.ok(breakdown);
    }
}
