package io.github.llm4j.agent.rag.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.pgvector.PGvector;
import io.github.llm4j.agent.rag.store.VectorStore.SearchResult;
import java.sql.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class PGVectorStoreTest {

    @Mock private Connection mockConnection;
    @Mock private Statement mockStatement;
    @Mock private PreparedStatement mockPreparedStatement;
    @Mock private ResultSet mockResultSet;

    private PGVectorStore vectorStore;

    @BeforeEach
    void setUp() throws SQLException {
        // Setup default mocks for initialization
        when(mockConnection.createStatement()).thenReturn(mockStatement);
        // Lenient because some tests might not trigger all SQLs
        lenient()
                .when(mockConnection.prepareStatement(anyString()))
                .thenReturn(mockPreparedStatement);
    }

    private void initVectorStore() {
        vectorStore =
                new PGVectorStore(
                        "jdbc:postgresql://localhost:5432/test",
                        "user",
                        "pass",
                        "embeddings",
                        384) {
                    @Override
                    protected Connection getConnection() {
                        return mockConnection;
                    }
                };
    }

    @Test
    void testConstructor_shouldInitializeDatabase() throws SQLException {
        initVectorStore();

        verify(mockStatement).execute("CREATE EXTENSION IF NOT EXISTS vector");
        verify(mockStatement).execute(contains("CREATE TABLE IF NOT EXISTS embeddings"));
    }

    @Test
    void testAdd_shouldInsertVector() throws SQLException {
        initVectorStore();

        String id = "doc1";
        float[] embedding = new float[] {0.1f, 0.2f};
        Map<String, Object> metadata = Map.of("key", "value");

        vectorStore.add(id, embedding, metadata);

        verify(mockConnection).prepareStatement(contains("INSERT INTO embeddings"));
        verify(mockPreparedStatement).setString(eq(1), eq(id));
        verify(mockPreparedStatement)
                .setObject(
                        eq(2),
                        any(PGvector.class)); // Can't easily match PGvector equality without equals
        verify(mockPreparedStatement).setString(eq(3), contains("{\"key\":\"value\"}"));
        verify(mockPreparedStatement).executeUpdate();
    }

    @Test
    void testSearch_shouldReturnResults() throws SQLException {
        initVectorStore();

        // Mock search result
        // when(mockStatement.executeQuery(anyString())).thenReturn(mockResultSet); // Not used in
        // search
        when(mockPreparedStatement.executeQuery()).thenReturn(mockResultSet);

        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString("id")).thenReturn("doc1");
        when(mockResultSet.getDouble("distance")).thenReturn(0.1);
        when(mockResultSet.getString("metadata")).thenReturn("{\"key\":\"value\"}");

        float[] query = new float[] {0.1f, 0.2f};
        List<SearchResult> results = vectorStore.search(query, 5);

        assertEquals(1, results.size());
        assertEquals("doc1", results.get(0).getId());
        assertEquals(0.9f, results.get(0).getSimilarity(), 0.001f); // 1.0 - 0.1
        assertEquals("value", results.get(0).getMetadata().get("key"));
    }

    @Test
    void testDelete_shouldExecuteDelete() throws SQLException {
        initVectorStore();

        when(mockPreparedStatement.executeUpdate()).thenReturn(1);

        boolean deleted = vectorStore.delete("doc1");

        assertTrue(deleted);
        verify(mockConnection).prepareStatement(contains("DELETE FROM embeddings"));
        verify(mockPreparedStatement).setString(1, "doc1");
    }

    @Test
    void testSize_shouldReturnCount() throws SQLException {
        initVectorStore();

        when(mockStatement.executeQuery(contains("SELECT COUNT(*)"))).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(42);

        int size = vectorStore.size();

        assertEquals(42, size);
    }
}
