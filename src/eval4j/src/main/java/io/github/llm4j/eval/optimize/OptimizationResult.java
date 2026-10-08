package io.github.llm4j.eval.optimize;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of a run. {@code best} is the highest-validation-scoring candidate found, but only
 * trust it if {@link #generalized()} is true — that verdict combines the sealed test split, a
 * confirmation re-run and the guardrails.
 *
 * @param bestSelectionValidationMean the validation mean that made {@code best} the winner during
 *     the search; compare with {@code bestScores.validationMean()} (the confirmation run) to see
 *     how much of it was noise
 * @param seedVsBest paired test-split comparison; null when there is no test split or no
 *     improvement was found
 */
public record OptimizationResult(
        Candidate seed,
        Candidate best,
        StopReason stopReason,
        CandidateScores seedScores,
        CandidateScores bestScores,
        double bestSelectionValidationMean,
        Verdict verdict,
        Comparison seedVsBest,
        Cost cost,
        List<Round> trace,
        List<String> warnings) {

    public OptimizationResult {
        trace = List.copyOf(trace);
        warnings = List.copyOf(warnings);
    }

    /** Whether the best candidate is a verified, generalizing improvement. */
    public boolean generalized() {
        return verdict.generalized();
    }

    /** A reviewable diff of seed versus best. Writes nothing. */
    public PromptPatch toPatch() {
        return new PromptPatch(seed.parameters(), best.parameters());
    }

    /**
     * Writes {@code optimizer-trace.json} and {@code optimizer-report.md} into {@code directory}.
     */
    public void writeReport(Path directory) {
        OptimizationReport.write(this, directory);
    }
}
