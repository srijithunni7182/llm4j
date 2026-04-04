package io.github.llm4j.agent.memory;

import java.util.Objects;

/**
 * Configuration for setting up Semantic Memory in a {@link io.github.llm4j.agent.ReActAgent}.
 * Pass to the agent builder via {@code .semanticMemoryConfig(config)} to get automatic
 * long-term memory with no additional boilerplate.
 *
 * <b>In-Memory (Development)</b>
 * <pre>
 * SemanticMemoryConfig config = SemanticMemoryConfig.inMemory("user-123");
 * </pre>
 *
 * <b>Production (pgvector + ONNX)</b>
 * <pre>
 * SemanticMemoryConfig config = SemanticMemoryConfig.builder()
 *     .userId("user-123")
 *     .onnxModelPath("/models/all-MiniLM-L6-v2.onnx")
 *     .onnxTokenizerPath("/models/tokenizer.json")
 *     .pgUrl("jdbc:postgresql://localhost:5432/agentdb")
 *     .pgUser("postgres")
 *     .pgPassword("password")
 *     .pgTable("agent_memories")
 *     .build();
 * </pre>
 *
 * <b>Production (Gemini Cloud Embeddings)</b>
 * <pre>
 * SemanticMemoryConfig config = SemanticMemoryConfig.builder()
 *     .userId("user-123")
 *     .geminiApiKey(System.getenv("GOOGLE_API_KEY"))
 *     .build(); // uses InMemoryVectorStore by default
 * </pre>
 */
public class SemanticMemoryConfig {

    public enum EmbeddingMode { IN_MEMORY, ONNX, GEMINI }
    public enum StoreMode { IN_MEMORY, PGVECTOR }

    private final String userId;
    private final EmbeddingMode embeddingMode;
    private final StoreMode storeMode;

    // ONNX fields
    private final String onnxModelPath;
    private final String onnxTokenizerPath;

    // Gemini fields
    private final String geminiApiKey;
    private final String geminiEmbeddingModel;

    // PGVector fields
    private final String pgUrl;
    private final String pgUser;
    private final String pgPassword;
    private final String pgTable;
    private final int pgDimension;

    // Memory retrieval settings
    private final int topK;
    private final float similarityThreshold;

    private SemanticMemoryConfig(Builder b) {
        this.userId = Objects.requireNonNull(b.userId, "userId is required");
        this.embeddingMode = b.embeddingMode;
        this.storeMode = b.storeMode;
        this.onnxModelPath = b.onnxModelPath;
        this.onnxTokenizerPath = b.onnxTokenizerPath;
        this.geminiApiKey = b.geminiApiKey;
        this.geminiEmbeddingModel = b.geminiEmbeddingModel;
        this.pgUrl = b.pgUrl;
        this.pgUser = b.pgUser;
        this.pgPassword = b.pgPassword;
        this.pgTable = b.pgTable;
        this.pgDimension = b.pgDimension;
        this.topK = b.topK;
        this.similarityThreshold = b.similarityThreshold;
    }

    /** Create a zero-config, in-memory semantic memory setup (ideal for development). */
    public static SemanticMemoryConfig inMemory(String userId) {
        return new Builder().userId(userId).build();
    }

    public String getUserId() { return userId; }
    public EmbeddingMode getEmbeddingMode() { return embeddingMode; }
    public StoreMode getStoreMode() { return storeMode; }
    public String getOnnxModelPath() { return onnxModelPath; }
    public String getOnnxTokenizerPath() { return onnxTokenizerPath; }
    public String getGeminiApiKey() { return geminiApiKey; }
    public String getGeminiEmbeddingModel() { return geminiEmbeddingModel; }
    public String getPgUrl() { return pgUrl; }
    public String getPgUser() { return pgUser; }
    public String getPgPassword() { return pgPassword; }
    public String getPgTable() { return pgTable; }
    public int getPgDimension() { return pgDimension; }
    public int getTopK() { return topK; }
    public float getSimilarityThreshold() { return similarityThreshold; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private EmbeddingMode embeddingMode = EmbeddingMode.IN_MEMORY;
        private StoreMode storeMode = StoreMode.IN_MEMORY;
        private String onnxModelPath;
        private String onnxTokenizerPath;
        private String geminiApiKey;
        private String geminiEmbeddingModel = "text-embedding-004";
        private String pgUrl;
        private String pgUser;
        private String pgPassword;
        private String pgTable = "agent_memories";
        private int pgDimension = 384;
        private int topK = 5;
        private float similarityThreshold = 0.7f;

        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder onnxModelPath(String path) { this.onnxModelPath = path; this.embeddingMode = EmbeddingMode.ONNX; return this; }
        public Builder onnxTokenizerPath(String path) { this.onnxTokenizerPath = path; return this; }
        public Builder geminiApiKey(String key) { this.geminiApiKey = key; this.embeddingMode = EmbeddingMode.GEMINI; return this; }
        public Builder geminiEmbeddingModel(String model) { this.geminiEmbeddingModel = model; return this; }
        public Builder pgUrl(String url) { this.pgUrl = url; this.storeMode = StoreMode.PGVECTOR; return this; }
        public Builder pgUser(String user) { this.pgUser = user; return this; }
        public Builder pgPassword(String password) { this.pgPassword = password; return this; }
        public Builder pgTable(String table) { this.pgTable = table; return this; }
        public Builder pgDimension(int dimension) { this.pgDimension = dimension; return this; }
        public Builder topK(int topK) { this.topK = topK; return this; }
        public Builder similarityThreshold(float threshold) { this.similarityThreshold = threshold; return this; }

        public SemanticMemoryConfig build() { return new SemanticMemoryConfig(this); }
    }
}
