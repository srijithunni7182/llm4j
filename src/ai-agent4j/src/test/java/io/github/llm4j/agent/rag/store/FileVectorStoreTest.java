package io.github.llm4j.agent.rag.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileVectorStoreTest {

    @TempDir Path tempDir;

    @Test
    void testPersistence() {
        File vectorFile = tempDir.resolve("vectors.json").toFile();

        // 1. Create Store and Add Data
        FileVectorStore store1 = new FileVectorStore(vectorFile);
        float[] embedding = {0.1f, 0.2f, 0.3f};
        store1.add("doc1", embedding, Map.of("author", "srijith"));

        // Data should be in memory
        assertThat(store1.size()).isEqualTo(1);
        // Data should be on disk (file exists and not empty)
        assertThat(vectorFile).exists();
        assertThat(vectorFile.length()).isGreaterThan(0);

        // 2. "Restart" - Create new store from same file
        FileVectorStore store2 = new FileVectorStore(vectorFile);

        // Verify data loaded
        assertThat(store2.size()).isEqualTo(1);
        List<VectorStore.SearchResult> results = store2.search(embedding, 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("doc1");
        assertThat(results.get(0).getMetadata()).containsEntry("author", "srijith");
    }

    @Test
    void testFileAutoCreation() {
        File nonExistentFile = tempDir.resolve("nested/dir/store.json").toFile();
        FileVectorStore store = new FileVectorStore(nonExistentFile);

        store.add("doc1", new float[] {1.0f}, Map.of());

        assertThat(nonExistentFile).exists();
    }
}
