package io.github.llm4j.agent.rag.embedding;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class OnnxEmbeddingProviderIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OnnxEmbeddingProviderIntegrationTest.class);
    private static OnnxEmbeddingProvider provider;

    @BeforeAll
    static void setUp() throws Exception {
        Path modelPath = Paths.get("src/test/resources/models/onnx_minilm/model.onnx");
        Path tokenizerPath = Paths.get("src/test/resources/models/onnx_minilm/tokenizer.json");

        if (!modelPath.toFile().exists() || !tokenizerPath.toFile().exists()) {
            throw new RuntimeException("Test models not found. Run scripts/setup_test_models.sh first.");
        }

        provider = new OnnxEmbeddingProvider(modelPath, tokenizerPath);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    void testEmbed() {
        String text = "The quick brown fox jumps over the lazy dog";
        float[] embedding = provider.embed(text);

        assertThat(embedding).isNotNull();
        assertThat(embedding.length).isEqualTo(provider.getDimensions());
        assertThat(provider.getDimensions()).isEqualTo(384); // all-MiniLM-L6-v2 is 384d

        // Verify normalization (L2 norm should be close to 1.0)
        double norm = 0;
        for (float f : embedding)
            norm += f * f;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void testEmbedBatch() {
        List<String> texts = Arrays.asList(
                "First sentence for testing",
                "Second sentence with more words than the first one");
        List<float[]> embeddings = provider.embedBatch(texts);

        assertThat(embeddings).hasSize(2);
        for (float[] embedding : embeddings) {
            assertThat(embedding.length).isEqualTo(384);
        }
    }
}
