package io.github.llm4j.engram.core;

import io.github.llm4j.engram.core.models.MemoryObject;
import io.github.llm4j.engram.core.models.MemoryTier;
import io.github.llm4j.engram.core.models.ScoredMemory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PGVectorStore using Testcontainers (pgvector image).
 *
 * Requires Docker. Run with: mvn test -Dtest=PGVectorStoreIntegrationTest
 */
class PGVectorStoreIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    private static PGVectorStore store;

    @BeforeAll
    static void setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                isDockerAvailable(),
                "Docker is not available; skipping PGVectorStoreIntegrationTest"
        );
        postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("engram_test")
                .withUsername("engram")
                .withPassword("engram_secret");
        postgres.start();
        store = new PGVectorStore(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    private static boolean isDockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void nearestIsPureSimilarityWithinATopicPrefix() {
        String content = "Casting lens: the thermodynamics of cooking pasta";
        store.add(new MemoryObject(content, store.embed(content), MemoryTier.EPISODIC, 0.1, "novelty-test:lens:1"));
        String other = "Casting lens: the thermodynamics of cooking noodles";
        store.add(new MemoryObject(other, store.embed(other), MemoryTier.EPISODIC, 0.1, "other-topic:1"));

        List<ScoredMemory> near = store.nearest("the thermodynamics of cooking pasta", "novelty-test:", 5);
        assertEquals(1, near.size(), "only memories under the topic prefix are compared");
        assertEquals(content, near.get(0).memory().getContent());
        assertTrue(near.get(0).score() > 0.8, "score is plain cosine similarity: " + near.get(0).score());
    }

    @Test
    void testAddAndScore() {
        // Arrange: store a Redis-related memory
        float[] embedding = store.embed("Redis is used as the caching layer with a 15-minute TTL");
        MemoryObject memory = new MemoryObject(
                "Redis is used as the caching layer with a 15-minute TTL",
                embedding,
                MemoryTier.SEMANTIC,
                0.9,
                "caching-strategy"
        );
        store.add(memory);

        // Act: query with a semantically similar question
        List<ScoredMemory> results = store.scoreCandidates(
                "What is the caching configuration for the user service?", 5, 0.05);

        // Assert: Redis fact surfaced
        assertFalse(results.isEmpty(), "Should find at least one candidate");
        ScoredMemory top = results.get(0);
        assertTrue(top.memory().getContent().contains("Redis"), "Top result should be Redis memory");
        System.out.println("PGVectorStore candidate (score=" + top.score() + "): " + top.memory().getContent());
    }

    @Test
    void testRemoveByContent() {
        // Arrange: store a bad memory
        float[] embedding = store.embed("Use MySQL for all caching — it is fast enough");
        MemoryObject bad = new MemoryObject(
                "Use MySQL for all caching — it is fast enough",
                embedding,
                MemoryTier.EPISODIC,
                0.3,
                null
        );
        store.add(bad);

        // Act: remove it
        store.removeByContent("Use MySQL for all caching — it is fast enough");

        // Assert: not found anymore
        List<ScoredMemory> results = store.scoreCandidates("MySQL caching", 10, 0.0);
        results.forEach(sm ->
                assertFalse(sm.memory().getContent().contains("MySQL for all caching"),
                        "Bad memory should have been pruned"));

        System.out.println("PGVectorStore removeByContent: bad memory successfully pruned.");
    }
}
