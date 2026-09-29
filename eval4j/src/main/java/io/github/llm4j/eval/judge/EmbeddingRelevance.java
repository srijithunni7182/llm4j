package io.github.llm4j.eval.judge;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import java.util.List;
import java.util.Objects;

/**
 * Embedding-based contextual relevancy/precision: no judge LLM, one embedding per chunk. Accepts any
 * {@link EmbeddingProvider} — including {@code ai-agent4j-addons}' local ONNX/DJL providers. A chunk
 * counts as relevant when its cosine similarity to the input is at least {@code
 * similarityThreshold}. Recall is judge-only and not offered here.
 *
 * <pre>{@code
 * assertThat(result).is(EmbeddingRelevance.using(onnxProvider).contextualRelevancy(q, chunks, 0.6));
 * }</pre>
 */
public final class EmbeddingRelevance {

    private static final double DEFAULT_SIMILARITY = 0.5;

    private final EmbeddingProvider provider;
    private final double similarityThreshold;

    private EmbeddingRelevance(EmbeddingProvider provider, double similarityThreshold) {
        this.provider = provider;
        this.similarityThreshold = similarityThreshold;
    }

    public static EmbeddingRelevance using(EmbeddingProvider provider) {
        return new EmbeddingRelevance(
                Objects.requireNonNull(provider, "provider cannot be null"), DEFAULT_SIMILARITY);
    }

    /** Cosine-similarity cutoff for a chunk to count as relevant (default 0.5). */
    public EmbeddingRelevance similarityThreshold(double similarityThreshold) {
        return new EmbeddingRelevance(provider, similarityThreshold);
    }

    public RagContextCondition contextualRelevancy(
            String input, List<String> retrievalContext, double threshold) {
        return build(RagContextCondition.Metric.RELEVANCY, input, retrievalContext, threshold);
    }

    public RagContextCondition contextualPrecision(
            String input, List<String> retrievalContext, double threshold) {
        return build(RagContextCondition.Metric.PRECISION, input, retrievalContext, threshold);
    }

    private RagContextCondition build(
            RagContextCondition.Metric metric,
            String input,
            List<String> retrievalContext,
            double threshold) {
        return RagContextCondition.builder(metric)
                .input(input)
                .retrievalContext(retrievalContext)
                .threshold(threshold)
                .embeddings(provider, similarityThreshold)
                .build();
    }
}
