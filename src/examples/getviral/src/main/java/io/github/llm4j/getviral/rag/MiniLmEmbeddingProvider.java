package io.github.llm4j.getviral.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import java.util.List;

/**
 * Zero-setup local embeddings: the all-MiniLM-L6-v2 model that ships inside Engram's classpath,
 * adapted to ai-agent4j's {@link EmbeddingProvider}. Used when no ONNX model files are configured
 * for the addons' {@code OnnxEmbeddingProvider}.
 */
public class MiniLmEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public int getDimensions() {
        return 384;
    }
}
