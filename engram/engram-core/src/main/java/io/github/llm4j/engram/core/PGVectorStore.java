package io.github.llm4j.engram.core;

import com.pgvector.PGvector;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.engram.core.models.ScoredMemory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Production VectorStore backed by PostgreSQL with the pgvector extension.
 *
 * <p>This store uses AllMiniLmL6V2 (384-dim) embeddings and persists them as pgvector columns.
 * Scoring (Similarity, Recency, Reinforcement, Importance, Decay) is performed in-Java
 * after a candidate retrieval using pgvector's cosine-distance operator (&lt;=&gt;) for a fast
 * approximate pre-filter, followed by an exact re-rank.
 *
 * <h3>Prerequisite</h3>
 * The database must have the pgvector extension installed:
 * <pre>CREATE EXTENSION IF NOT EXISTS vector;</pre>
 *
 * <p>Table {@code engram_memories} is created automatically on first use.
 */
public class PGVectorStore implements VectorStore {

    // --- Schema ---
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS engram_memories (
                id              TEXT PRIMARY KEY,
                content         TEXT NOT NULL,
                embedding       vector(384),
                tier            TEXT NOT NULL,
                importance      DOUBLE PRECISION NOT NULL,
                topic_key       TEXT,
                reinforcement   INTEGER NOT NULL DEFAULT 0,
                shadow          BOOLEAN NOT NULL DEFAULT FALSE,
                last_accessed   TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String INSERT_SQL = """
            INSERT INTO engram_memories
              (id, content, embedding, tier, importance, topic_key, reinforcement, shadow, last_accessed)
            VALUES (?, ?, ?::vector, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String SHADOW_BY_TOPIC_SQL = """
            UPDATE engram_memories SET shadow = TRUE
            WHERE topic_key = ? AND shadow = FALSE AND id != ?
            """;

    private static final String DELETE_BY_CONTENT_SQL = """
            DELETE FROM engram_memories WHERE content = ?
            """;

    private static final String FETCH_CANDIDATES_SQL = """
            SELECT id, content, embedding::text, tier, importance, topic_key, reinforcement, shadow, last_accessed
            FROM engram_memories
            WHERE shadow = FALSE
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    private static final String UPDATE_ACCESS_SQL = """
            UPDATE engram_memories
            SET reinforcement = reinforcement + 1, last_accessed = ?
            WHERE id = ?
            """;

    // --- Scoring weights (same as InMemoryStore) ---
    private static final double W_SIMILARITY    = 0.35;
    private static final double W_RECENCY       = 0.15;
    private static final double W_REINFORCEMENT = 0.15;
    private static final double W_IMPORTANCE    = 0.10;
    private static final double DAMPING_FACTOR  = 0.3;

    // --- Pre-filter factor: retrieve this many extra to re-rank in Java ---
    private static final int PREFILTER_MULTIPLIER = 4;

    private final EmbeddingModel embeddingModel;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PGVectorStore(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        initSchema();
    }

    // -----------------------------------------------------------------------
    // Schema bootstrap
    // -----------------------------------------------------------------------

    private void initSchema() {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            // Register pgvector type so the JDBC driver handles vector columns
            PGvector.addVectorType(conn);
            st.execute(CREATE_TABLE_SQL);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise Engram pgvector schema", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    // -----------------------------------------------------------------------
    // VectorStore contract
    // -----------------------------------------------------------------------

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    @Override
    public void add(MemoryObject memory) {
        try (Connection conn = getConnection()) {
            PGvector.addVectorType(conn);

            // Shadow any existing non-shadow memories with the same topicKey
            if (memory.getTopicKey() != null && !memory.getTopicKey().isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement(SHADOW_BY_TOPIC_SQL)) {
                    ps.setString(1, memory.getTopicKey());
                    ps.setString(2, memory.getId());
                    ps.executeUpdate();
                }
            }

            // Insert the new memory
            try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
                ps.setString(1, memory.getId());
                ps.setString(2, memory.getContent());
                ps.setObject(3, toPgVectorLiteral(memory.getEmbedding()));
                ps.setString(4, memory.getTier().name());
                ps.setDouble(5, memory.getImportance());
                ps.setString(6, memory.getTopicKey());
                ps.setInt(7, memory.getReinforcementCount());
                ps.setBoolean(8, memory.isShadow());
                ps.setTimestamp(9, Timestamp.from(memory.getLastAccessedAt()));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("PGVectorStore: failed to add memory — " + e.getMessage());
        }
    }

    @Override
    public void removeByContent(String content) {
        if (content == null || content.isBlank()) return;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_CONTENT_SQL)) {
            ps.setString(1, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("PGVectorStore: failed to delete memory — " + e.getMessage());
        }
    }

    @Override
    public List<ScoredMemory> scoreCandidates(String taskIntent, int topN, double minScore) {
        float[] taskVector = embed(taskIntent);
        String vectorLiteral = toPgVectorLiteral(taskVector);
        int prefilterLimit = topN * PREFILTER_MULTIPLIER;

        List<ScoredMemory> candidates = new ArrayList<>();

        try (Connection conn = getConnection()) {
            PGvector.addVectorType(conn);
            try (PreparedStatement ps = conn.prepareStatement(FETCH_CANDIDATES_SQL)) {
                ps.setObject(1, vectorLiteral);
                ps.setInt(2, prefilterLimit);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MemoryObject mem = mapRow(rs);

                        // Re-compute exact Engram score in Java
                        double similarity   = cosineSimilarity(taskVector, mem.getEmbedding());
                        double recency      = calculateRecencyScore(mem.getLastAccessedAt());
                        double reinforcement = Math.log10(1 + mem.getReinforcementCount());
                        double decay        = calculateDecay(mem);

                        double score = (W_SIMILARITY * similarity)
                                + (W_RECENCY * recency)
                                + (W_REINFORCEMENT * reinforcement)
                                + (W_IMPORTANCE * mem.getImportance())
                                - decay;

                        if (score >= minScore) {
                            candidates.add(new ScoredMemory(mem, score));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("PGVectorStore: failed to score candidates — " + e.getMessage());
        }

        candidates.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }

    @Override
    public void save() {
        // PGVectorStore commits eagerly — no-op
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Converts a float[] into a pgvector literal, e.g. "[0.1,0.2,0.3]". */
    private static String toPgVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /** Parses a pgvector literal back into a float[]. */
    private static float[] parsePgVectorLiteral(String literal) {
        if (literal == null) return new float[0];
        // Strip surrounding brackets
        String inner = literal.replaceAll("[\\[\\]]", "").trim();
        if (inner.isEmpty()) return new float[0];
        String[] parts = inner.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    private MemoryObject mapRow(ResultSet rs) throws SQLException {
        String id            = rs.getString("id");
        String content       = rs.getString("content");
        float[] embedding    = parsePgVectorLiteral(rs.getString("embedding"));
        MemoryTier tier      = MemoryTier.valueOf(rs.getString("tier"));
        double importance    = rs.getDouble("importance");
        String topicKey      = rs.getString("topic_key");
        int reinforcement    = rs.getInt("reinforcement");
        boolean shadow       = rs.getBoolean("shadow");
        Instant lastAccessed = rs.getTimestamp("last_accessed").toInstant();
        return new MemoryObject(id, content, embedding, tier, importance, topicKey, reinforcement, shadow, lastAccessed);
    }

    private double calculateDecay(MemoryObject memory) {
        double decayRate = switch (memory.getTier()) {
            case EPISODIC -> 0.05;
            case SEMANTIC -> 0.001;
            case WORKING  -> 0.0;
        };
        long elapsedHours = Duration.between(memory.getLastAccessedAt(), Instant.now()).toHours();
        if (elapsedHours < 0) elapsedHours = 0;
        return decayRate * Math.exp(-memory.getReinforcementCount() * DAMPING_FACTOR) * elapsedHours;
    }

    private double calculateRecencyScore(Instant lastAccessedAt) {
        long elapsedMinutes = Duration.between(lastAccessedAt, Instant.now()).toMinutes();
        if (elapsedMinutes < 0) return 1.0;
        return Math.max(0.0, 1.0 - (elapsedMinutes / 1440.0));
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dot   += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
