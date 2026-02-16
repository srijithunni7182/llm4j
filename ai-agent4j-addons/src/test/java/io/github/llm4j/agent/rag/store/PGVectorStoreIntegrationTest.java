package io.github.llm4j.agent.rag.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PGVectorStoreIntegrationTest {

    static DockerImageName IMAGE = DockerImageName.parse("pgvector/pgvector:pg16");

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("vectordb")
                    .withUsername("test")
                    .withPassword("test");

    static PGVectorStore vectorStore;

    @BeforeAll
    static void setUp() {
        vectorStore =
                new PGVectorStore(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword(),
                        "test_embeddings",
                        3 // Dimension 3 for testing
                        );
    }

    @Test
    void testVectorOperations() {
        // 1. Add Vector
        String id1 = "doc1";
        float[] embedding1 = {0.1f, 0.2f, 0.3f};
        Map<String, Object> metadata = Map.of("category", "test");

        vectorStore.add(id1, embedding1, metadata);

        assertThat(vectorStore.size()).isEqualTo(1);

        // 2. Search exact match
        List<VectorStore.SearchResult> results = vectorStore.search(embedding1, 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(id1);
        assertThat(results.get(0).getSimilarity())
                .isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.0001f));
        assertThat(results.get(0).getMetadata()).containsEntry("category", "test");

        // 3. Add second vector
        String id2 = "doc2";
        float[] embedding2 = {0.9f, 0.8f, 0.7f};
        vectorStore.add(id2, embedding2, Map.of());

        assertThat(vectorStore.size()).isEqualTo(2);

        // 4. Delete
        vectorStore.delete(id1);
        assertThat(vectorStore.size()).isEqualTo(1);

        // 5. Clear
        vectorStore.clear();
        assertThat(vectorStore.size()).isEqualTo(0);
    }

    @Test
    void testUpsert() {
        String id = "upsert_doc";
        vectorStore.add(id, new float[] {0.1f, 0.1f, 0.1f}, Map.of("v", 1));

        // Update same ID with new vector
        vectorStore.add(id, new float[] {0.9f, 0.9f, 0.9f}, Map.of("v", 2));

        assertThat(vectorStore.size()).isEqualTo(1);
        List<VectorStore.SearchResult> results =
                vectorStore.search(new float[] {0.9f, 0.9f, 0.9f}, 1);
        assertThat(results.get(0).getSimilarity())
                .isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(results.get(0).getMetadata()).containsEntry("v", 2);
    }
}
