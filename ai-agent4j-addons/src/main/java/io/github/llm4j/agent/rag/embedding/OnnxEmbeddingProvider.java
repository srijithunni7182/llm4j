package io.github.llm4j.agent.rag.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local embedding provider using ONNX Runtime. Requires an ONNX model file and a tokenizer.json
 * file.
 */
public class OnnxEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OnnxEmbeddingProvider.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int dimensions;

    public OnnxEmbeddingProvider(String modelPath, String tokenizerPath) throws Exception {
        this(Paths.get(modelPath), Paths.get(tokenizerPath));
    }

    public OnnxEmbeddingProvider(Path modelPath, Path tokenizerPath) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        try {
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        } catch (java.io.IOException e) {
            throw new java.io.IOException("Failed to load tokenizer from: " + tokenizerPath, e);
        }

        // Extract dimensions from model output
        TensorInfo info = (TensorInfo) session.getOutputInfo().values().iterator().next().getInfo();
        long[] shape = info.getShape();
        this.dimensions = (int) shape[shape.length - 1];

        logger.info(
                "Initialized OnnxEmbeddingProvider with model: {} and dimensions: {}",
                modelPath,
                dimensions);
    }

    OnnxEmbeddingProvider(OrtEnvironment env, OrtSession session, HuggingFaceTokenizer tokenizer)
            throws OrtException {
        this.env = env;
        this.session = session;
        this.tokenizer = tokenizer;

        // Extract dimensions from model output
        TensorInfo info = (TensorInfo) session.getOutputInfo().values().iterator().next().getInfo();
        long[] shape = info.getShape();
        this.dimensions = (int) shape[shape.length - 1];
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(Collections.singletonList(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        try {
            List<float[]> embeddings = new ArrayList<>();
            for (String text : texts) {
                Encoding encoding = tokenizer.encode(text);
                long[] inputIds = encoding.getIds();
                long[] attentionMask = encoding.getAttentionMask();
                long[] typeIds = encoding.getTypeIds();

                long[][] inputIdsBatch = new long[1][inputIds.length];
                long[][] attentionMaskBatch = new long[1][attentionMask.length];
                long[][] typeIdsBatch = new long[1][typeIds.length];

                inputIdsBatch[0] = inputIds;
                attentionMaskBatch[0] = attentionMask;
                typeIdsBatch[0] = typeIds;

                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", OnnxTensor.createTensor(env, inputIdsBatch));
                inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMaskBatch));
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, typeIdsBatch));

                try (OrtSession.Result results = session.run(inputs)) {
                    // Assuming the first output is the last_hidden_state or pooler_output
                    // For most Sentence Transformers, [0] is the output.
                    // We might need to handle mean pooling if it's not done in the model.
                    float[][][] lastHiddenState = (float[][][]) results.get(0).getValue();

                    // Simple Mean Pooling
                    float[] embedding = meanPool(lastHiddenState[0], attentionMask);
                    embeddings.add(normalize(embedding));
                }
            }
            return embeddings;
        } catch (OrtException e) {
            logger.error("Failed to generate ONNX embeddings", e);
            throw new RuntimeException("Failed to generate ONNX embeddings", e);
        }
    }

    private float[] meanPool(float[][] lastHiddenState, long[] attentionMask) {
        int seqLength = lastHiddenState.length;
        int hiddenSize = lastHiddenState[0].length;
        float[] mean = new float[hiddenSize];
        int count = 0;

        for (int i = 0; i < seqLength; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < hiddenSize; j++) {
                    mean[j] += lastHiddenState[i][j];
                }
                count++;
            }
        }

        if (count > 0) {
            for (int j = 0; j < hiddenSize; j++) {
                mean[j] /= count;
            }
        }
        return mean;
    }

    private float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) sum += f * f;
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= norm;
        }
        return v;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }

    @Override
    public void close() throws OrtException {
        if (session != null) session.close();
        if (env != null) env.close();
        if (tokenizer != null) tokenizer.close();
    }
}
