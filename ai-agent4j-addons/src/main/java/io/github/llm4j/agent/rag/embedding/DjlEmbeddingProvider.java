package io.github.llm4j.agent.rag.embedding;

import ai.djl.ModelException;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local embedding provider using Deep Java Library (DJL). Supports PyTorch, TensorFlow, and other
 * engines based on registered dependencies.
 */
public class DjlEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DjlEmbeddingProvider.class);

    private final ZooModel<String, float[]> model;
    private final Predictor<String, float[]> predictor;
    private final int dimensions;

    public DjlEmbeddingProvider(String modelUrl) throws ModelException, IOException {
        this(modelUrl, null);
    }

    public DjlEmbeddingProvider(String modelUrl, String engine) throws ModelException, IOException {
        Criteria.Builder<String, float[]> builder =
                Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optModelUrls(modelUrl)
                        .optProgress(new ProgressBar());

        if (engine != null) {
            builder.optEngine(engine);
        }

        // Use a default translator for common BERT-style models
        builder.optTranslator(new DefaultBertTranslator());

        this.model = builder.build().loadModel();
        this.predictor = model.newPredictor();

        // Dry run to get dimensions
        try {
            float[] dummy = predictor.predict("dummy");
            this.dimensions = dummy.length;
        } catch (TranslateException e) {
            throw new IOException("Failed to perform dry run for dimension extraction", e);
        }

        logger.info(
                "Initialized DjlEmbeddingProvider with model: {} and dimensions: {}",
                modelUrl,
                dimensions);
    }

    DjlEmbeddingProvider(
            ZooModel<String, float[]> model, Predictor<String, float[]> predictor, int dimensions) {
        this.model = model;
        this.predictor = predictor;
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        try {
            return predictor.predict(text);
        } catch (TranslateException e) {
            logger.error("Failed to generate DJL embedding", e);
            throw new RuntimeException("Failed to generate DJL embedding", e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        try {
            return predictor.batchPredict(texts);
        } catch (TranslateException e) {
            logger.error("Failed to generate DJL batch embeddings", e);
            throw new RuntimeException("Failed to generate DJL batch embeddings", e);
        }
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }

    /** A basic translator for BERT-like embedding models. */
    private static class DefaultBertTranslator implements Translator<String, float[]> {
        private HuggingFaceTokenizer tokenizer;

        @Override
        public void prepare(TranslatorContext ctx) throws IOException {
            Path modelPath = ctx.getModel().getModelPath();
            Path tokenizerPath = modelPath.resolve("tokenizer.json");
            this.tokenizer =
                    HuggingFaceTokenizer.builder()
                            .optTokenizerPath(tokenizerPath)
                            .optPadding(true)
                            .optTruncation(true)
                            .optMaxLength(512)
                            .build();
        }

        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            Encoding encoding = tokenizer.encode(input);
            long[] indices = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] typeIds = encoding.getTypeIds();

            int targetLength = 512;

            // Manual Padding/Truncation
            if (indices.length > targetLength) {
                indices = java.util.Arrays.copyOf(indices, targetLength);
                attentionMask = java.util.Arrays.copyOf(attentionMask, targetLength);
                typeIds = java.util.Arrays.copyOf(typeIds, targetLength);
            } else if (indices.length < targetLength) {
                long[] paddedIndices = new long[targetLength];
                long[] paddedMask = new long[targetLength];
                long[] paddedTypeIds = new long[targetLength];

                System.arraycopy(indices, 0, paddedIndices, 0, indices.length);
                System.arraycopy(attentionMask, 0, paddedMask, 0, attentionMask.length);
                System.arraycopy(typeIds, 0, paddedTypeIds, 0, typeIds.length);

                indices = paddedIndices;
                attentionMask = paddedMask;
                typeIds = paddedTypeIds;
            }

            NDManager manager = ctx.getNDManager();
            NDArray indicesArray = manager.create(indices);
            NDArray attentionMaskArray = manager.create(attentionMask);
            NDArray typeIdsArray = manager.create(typeIds);

            // Explicitly name the inputs if the engine supports it (like ONNX)
            indicesArray.setName("input_ids");
            attentionMaskArray.setName("attention_mask");
            typeIdsArray.setName("token_type_ids");

            return new NDList(indicesArray, attentionMaskArray, typeIdsArray);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // Standard Bert model output: [last_hidden_state, pooler_output]
            NDArray lastHiddenState = list.get(0);

            // Simple Mean Pooling
            NDArray meanPooled = lastHiddenState.mean(new int[] {0});

            // Normalize
            NDArray normalized = meanPooled.div(meanPooled.norm());

            return normalized.toFloatArray();
        }

        public void close() {
            if (tokenizer != null) tokenizer.close();
        }
    }
}
