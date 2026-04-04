package io.github.llm4j.agent.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.*;
import java.util.*;

import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.agent.rag.store.VectorStore.SearchResult;
import io.github.llm4j.agent.rag.store.VectorStore.VectorEntry;

/**
 * Persistent vector store using PostgreSQL and the 'pgvector' extension. Requires a running
 * PostgreSQL instance with 'vector' extension installed. Add 'postgresql' and 'pgvector'
 * dependencies to use this.
 */
public class PGVectorStore implements VectorStore {

    private final String url;
    private final String user;
    private final String password;
    private final String tableName;
    private final int dimension;
    private final ObjectMapper objectMapper;

    /**
     * @param url JDBC URL (e.g., jdbc:postgresql://localhost:5432/mydb)
     * @param user Database user
     * @param password Database password
     * @param tableName Table name to store vectors
     * @param dimension Dimension of vectors (must match model output, usually 384 or 768)
     */
    public PGVectorStore(
            String url, String user, String password, String tableName, int dimension) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.tableName = tableName;
        this.dimension = dimension;
        this.objectMapper = new ObjectMapper();

        init();
    }

    private void init() {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                // Enable extension
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                // Create table
                String sql =
                        String.format(
                                "CREATE TABLE IF NOT EXISTS %s ("
                                        + "id TEXT PRIMARY KEY, "
                                        + "embedding vector(%d), "
                                        + "metadata JSONB"
                                        + ")",
                                tableName, dimension);
                stmt.execute(sql);

                // Create index (optional but recommended for speed)
                // Using IVFFlat for speed, or HNSW for accuracy/speed balance
                // stmt.execute(String.format("CREATE INDEX IF NOT EXISTS %s_index ON %s USING
                // hnsw (embedding vector_cosine_ops)", tableName, tableName));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize PGVectorStore", e);
        }
    }

    protected Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(url, user, password);
        // Register vector type
        PGvector.addVectorType(conn);
        return conn;
    }

    @Override
    public void add(String id, float[] embedding, Map<String, Object> metadata) {
        String sql =
                String.format(
                        "INSERT INTO %s (id, embedding, metadata) VALUES (?, ?, ?::jsonb) "
                                + "ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, metadata = EXCLUDED.metadata",
                        tableName);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, id);
            stmt.setObject(2, new PGvector(embedding));
            stmt.setString(3, toJson(metadata));

            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add vector", e);
        }
    }

    @Override
    public void addBatch(List<VectorEntry> entries) {
        String sql =
                String.format(
                        "INSERT INTO %s (id, embedding, metadata) VALUES (?, ?, ?::jsonb) "
                                + "ON CONFLICT (id) DO UPDATE SET embedding = EXCLUDED.embedding, metadata = EXCLUDED.metadata",
                        tableName);

        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (VectorEntry entry : entries) {
                stmt.setString(1, entry.getId());
                stmt.setObject(2, new PGvector(entry.getEmbedding()));
                stmt.setString(3, toJson(entry.getMetadata()));
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to batch add vectors", e);
        }
    }

    @Override
    public List<SearchResult> search(
            float[] queryEmbedding, int topK, Map<String, Object> filters) {
        // Simple search without complex metadata filtering for now.
        // Implementing JSONB filtering dynamically is complex; avoiding for MVP unless
        // requested.
        // We filter in-memory if needed, but SQL filtering is better.
        // Let's implement basic cosine distance: <=> operator

        String sql =
                String.format(
                        "SELECT id, embedding, metadata, embedding <=> ? as distance FROM %s ORDER BY distance ASC LIMIT ?",
                        tableName);

        List<SearchResult> results = new ArrayList<>();
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, new PGvector(queryEmbedding));
            stmt.setInt(2, topK);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    // PGvector returns distance (0..2 usually for cosine distance).
                    // Similarity = 1 - distance (approx)
                    double distance = rs.getDouble("distance");
                    float similarity = (float) (1.0 - distance);

                    String metadataJson = rs.getString("metadata");
                    Map<String, Object> metadata = fromJson(metadataJson);

                    // Apply memory filters if any (fallback)
                    if (matchesFilters(metadata, filters)) {
                        results.add(new SearchResult(id, similarity, metadata));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Search failed", e);
        }
        return results;
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        return search(queryEmbedding, topK, null);
    }

    @Override
    public boolean delete(String id) {
        String sql = String.format("DELETE FROM %s WHERE id = ?", tableName);
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            int rows = stmt.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Delete failed", e);
        }
    }

    @Override
    public int size() {
        String sql = String.format("SELECT COUNT(*) FROM %s", tableName);
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Size check failed", e);
        }
    }

    @Override
    public void clear() {
        String sql = String.format("TRUNCATE TABLE %s", tableName);
        try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Clear failed", e);
        }
    }

    // --- Helpers ---

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map != null ? map : Collections.emptyMap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Duplicated from InMemoryVectorStore for now, ideally strictly in abstract
    // base
    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return true;
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object metaVal = metadata.get(filter.getKey());
            Object filterVal = filter.getValue();
            if (metaVal == null || !metaVal.equals(filterVal)) return false;
        }
        return true;
    }
}
