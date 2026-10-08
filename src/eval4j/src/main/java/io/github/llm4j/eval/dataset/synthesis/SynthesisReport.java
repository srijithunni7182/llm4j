package io.github.llm4j.eval.dataset.synthesis;

import java.util.List;

/**
 * What happened during synthesis: candidates {@code generated} (parsed successfully), {@code
 * filtered} out by the quality judge, dropped as {@code duplicates}, or {@code failed} (the
 * generator returned unusable output twice or threw), plus human-readable {@code warnings}.
 */
public record SynthesisReport(
        int generated, int filtered, int duplicates, int failed, List<String> warnings) {}
