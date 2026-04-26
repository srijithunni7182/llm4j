package io.github.llm4j.agent.memory;

import io.github.llm4j.agent.tool.MemoryManagementTool;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that assembles a fully-wired {@link SemanticMemoryService} and the associated
 * {@link io.github.llm4j.agent.tool.MemoryManagementTool} from a {@link SemanticMemoryConfig}.
 *
 * <p>Used internally by {@link io.github.llm4j.agent.ReActAgent.Builder} when
 * {@code semanticMemoryConfig()} is called, so callers never need to touch this class directly.
 */
public class SemanticMemoryFactory {

    private static final Logger logger = LoggerFactory.getLogger(SemanticMemoryFactory.class);

    private SemanticMemoryFactory() {}

    /**
     * Creates a fully-assembled {@link SemanticMemoryService} from the given config.
     * Handles all embedding provider and vector store wiring automatically.
     *
     * @param config the memory configuration
     * @return a ready-to-use SemanticMemoryService
     */
    public static SemanticMemoryService create(SemanticMemoryConfig config) {
        EmbeddingProvider embeddingProvider = buildEmbeddingProvider(config);
        VectorStore vectorStore = buildVectorStore(config, embeddingProvider);
        logger.info("SemanticMemoryService created [embedding={}, store={}] for user '{}'",
                config.getEmbeddingMode(), config.getStoreMode(), config.getUserId());
        return new SemanticMemoryService(embeddingProvider, vectorStore, config.getUserId());
    }

    /**
     * Creates the {@link MemoryManagementTool} from an assembled service.
     * Convenience method so the agent builder can register it in one call.
     */
    public static MemoryManagementTool createTool(SemanticMemoryService service) {
        return new MemoryManagementTool(service);
    }

    private static EmbeddingProvider buildEmbeddingProvider(SemanticMemoryConfig config) {
        return switch (config.getEmbeddingMode()) {
            case GEMINI -> {
                LLMConfig llmConfig = LLMConfig.builder()
                        .apiKey(config.getGeminiApiKey())
                        .build();
                yield new GeminiEmbeddingProvider(llmConfig, config.getGeminiEmbeddingModel());
            }
            case ONNX -> {
                // ONNX provider lives in ai-agent4j-addons — use reflection to avoid hard dep
                yield loadOnnxProvider(config.getOnnxModelPath(), config.getOnnxTokenizerPath());
            }
            case IN_MEMORY -> {
                logger.warn("Using stub IN_MEMORY embedding provider — all embeddings will be zero vectors. " +
                            "Only suitable for testing flows without real similarity search.");
                yield new ZeroEmbeddingProvider();
            }
        };
    }

    private static VectorStore buildVectorStore(SemanticMemoryConfig config, EmbeddingProvider provider) {
        return switch (config.getStoreMode()) {
            case PGVECTOR -> {
                // PGVectorStore lives in ai-agent4j-addons — use reflection to avoid hard dep
                yield loadPGVectorStore(config, provider);
            }
            case IN_MEMORY -> new InMemoryVectorStore();
        };
    }

    /**
     * Loads OnnxEmbeddingProvider reflectively so the core module doesn't depend on `ai-agent4j-addons`.
     */
    private static EmbeddingProvider loadOnnxProvider(String modelPath, String tokenizerPath) {
        try {
            Class<?> cls = Class.forName("io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider");
            Object instance = cls.getConstructor(String.class, String.class).newInstance(modelPath, tokenizerPath);
            return (EmbeddingProvider) instance;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "OnnxEmbeddingProvider not found on classpath. Add 'ai-agent4j-addons' dependency to use ONNX embeddings.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate OnnxEmbeddingProvider", e);
        }
    }

    /**
     * Loads PGVectorStore reflectively so the core module doesn't depend on `ai-agent4j-addons`.
     */
    private static VectorStore loadPGVectorStore(SemanticMemoryConfig config, EmbeddingProvider provider) {
        try {
            Class<?> cls = Class.forName("io.github.llm4j.agent.rag.store.PGVectorStore");
            Object instance = cls.getConstructor(String.class, String.class, String.class, String.class, int.class)
                    .newInstance(config.getPgUrl(), config.getPgUser(), config.getPgPassword(),
                                 config.getPgTable(), config.getPgDimension());
            return (VectorStore) instance;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "PGVectorStore not found on classpath. Add 'ai-agent4j-addons' dependency to use pgvector.", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate PGVectorStore", e);
        }
    }

    /**
     * A stub embedding provider that returns zero vectors.
     * Used for in-memory mode where cosine similarity is still exercised but semantic accuracy is irrelevant.
     */
    static class ZeroEmbeddingProvider implements EmbeddingProvider {
        private static final int DIMENSIONS = 384;

        @Override
        public float[] embed(String text) {
            return new float[DIMENSIONS];
        }

        @Override
        public java.util.List<float[]> embedBatch(java.util.List<String> texts) {
            return texts.stream().map(t -> new float[DIMENSIONS]).toList();
        }

        @Override
        public int getDimensions() {
            return DIMENSIONS;
        }
    }
}
