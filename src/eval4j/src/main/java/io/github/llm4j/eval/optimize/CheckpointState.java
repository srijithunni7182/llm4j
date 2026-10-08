package io.github.llm4j.eval.optimize;

import io.github.llm4j.eval.criteria.Scorecard;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to resume a run. Random draws are derived from (seed, round), so no RNG state.
 */
record CheckpointState(
        String fingerprint,
        int completedRounds,
        int noProgress,
        int consecutiveInfraFailures,
        int nextId,
        long rollouts,
        long rewriterCalls,
        long elapsedMillis,
        List<Scorecard> seedValidation,
        List<EntryState> entries,
        List<Map<String, String>> proposed,
        List<Round> trace) {

    record EntryState(
            CandidateSnapshot candidate, double[] validationScores, boolean guardrailViolation) {}
}
