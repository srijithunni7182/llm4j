package io.github.llm4j.eval.dataset.synthesis;

import java.util.List;

/** Immutable knobs for {@link DatasetSynthesizer}. Start from {@link #defaults()}. */
public final class SynthesisOptions {

    private final int scenariosPerDocument;
    private final List<Evolution> evolutions;
    private final Long seed;
    private final double temperature;
    private final double qualityThreshold;
    private final double similarityDedupThreshold;
    private final List<String> toolNames;

    private SynthesisOptions(
            int scenariosPerDocument,
            List<Evolution> evolutions,
            Long seed,
            double temperature,
            double qualityThreshold,
            double similarityDedupThreshold,
            List<String> toolNames) {
        this.scenariosPerDocument = scenariosPerDocument;
        this.evolutions = evolutions;
        this.seed = seed;
        this.temperature = temperature;
        this.qualityThreshold = qualityThreshold;
        this.similarityDedupThreshold = similarityDedupThreshold;
        this.toolNames = toolNames;
    }

    public static SynthesisOptions defaults() {
        return new SynthesisOptions(1, List.of(), null, 0.7, 0.6, 0.9, List.of());
    }

    public SynthesisOptions scenariosPerDocument(int n) {
        if (n < 1) {
            throw new IllegalArgumentException(
                    "scenariosPerDocument must be at least 1, got: " + n);
        }
        return new SynthesisOptions(
                n,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    /** Evolutions to apply; one is chosen (seeded) per scenario. Empty means none. */
    public SynthesisOptions evolutions(Evolution... evolutions) {
        return new SynthesisOptions(
                scenariosPerDocument,
                List.of(evolutions),
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    /** Makes chunk ordering and evolution choice reproducible. LLM text itself is not seeded. */
    public SynthesisOptions seed(long seed) {
        return new SynthesisOptions(
                scenariosPerDocument,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    public SynthesisOptions temperature(double temperature) {
        return new SynthesisOptions(
                scenariosPerDocument,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    /** Minimum quality score (0-1) a generated question must reach; default 0.6. */
    public SynthesisOptions qualityThreshold(double qualityThreshold) {
        return new SynthesisOptions(
                scenariosPerDocument,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    /** Cosine similarity above which two questions count as duplicates (needs embeddings). */
    public SynthesisOptions similarityDedupThreshold(double similarityDedupThreshold) {
        return new SynthesisOptions(
                scenariosPerDocument,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                toolNames);
    }

    /** Tool names an agent under test can call; scenario generation may reference them. */
    public SynthesisOptions toolNames(List<String> toolNames) {
        return new SynthesisOptions(
                scenariosPerDocument,
                evolutions,
                seed,
                temperature,
                qualityThreshold,
                similarityDedupThreshold,
                List.copyOf(toolNames));
    }

    public int scenariosPerDocument() {
        return scenariosPerDocument;
    }

    public List<Evolution> evolutions() {
        return evolutions;
    }

    public Long seed() {
        return seed;
    }

    public double temperature() {
        return temperature;
    }

    public double qualityThreshold() {
        return qualityThreshold;
    }

    public double similarityDedupThreshold() {
        return similarityDedupThreshold;
    }

    public List<String> toolNames() {
        return toolNames;
    }
}
