package io.github.llm4j.agent.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.config.LLMConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Embedding provider using Google Gemini's text-embedding-004 model.
 * Generates 768-dimensional embeddings.
 */
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger logger = LoggerFactory.getLogger(GeminiEmbeddingProvider.class);
    private static final String DEFAULT_MODEL = "text-embedding-004";
    private static final int EMBEDDING_DIMENSIONS = 768;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    public GeminiEmbeddingProvider(LLMConfig config) {
        this(config, DEFAULT_MODEL);
    }

    public GeminiEmbeddingProvider(LLMConfig config, String model) {
        this.apiKey = Objects.requireNonNull(config.getApiKey(), "API key cannot be null");
        this.model = model != null ? model : DEFAULT_MODEL;
        this.baseUrl = config.getBaseUrl() != null ? config.getBaseUrl()
                : "https://generativelanguage.googleapis.com/v1";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout())
                .readTimeout(config.getTimeout())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) {
        Objects.requireNonNull(text, "text cannot be null");

        try {
            String url = String.format("%s/models/%s:embedContent?key=%s",
                    baseUrl, model, apiKey);

            String requestBody = String.format(
                    "{\"content\":{\"parts\":[{\"text\":\"%s\"}]}}",
                    escapeJson(text));

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Embedding request failed: " + response);
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode valuesNode = root.path("embedding").path("values");

                if (valuesNode.isMissingNode()) {
                    throw new IOException("No embedding values in response");
                }

                float[] embedding = new float[valuesNode.size()];
                for (int i = 0; i < valuesNode.size(); i++) {
                    embedding[i] = (float) valuesNode.get(i).asDouble();
                }

                return embedding;
            }
        } catch (IOException e) {
            logger.error("Failed to generate embedding", e);
            throw new RuntimeException("Failed to generate embedding", e);
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts cannot be null");

        // Simple implementation: embed each text individually
        // Could be optimized with batch API if available
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return embeddings;
    }

    @Override
    public int getDimensions() {
        return EMBEDDING_DIMENSIONS;
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
