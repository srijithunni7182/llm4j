package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.rag.store.VectorStore;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A real pgvector round trip through the starter's settings. Needs a database with the vector extension: set LLM4J_TEST_PGVECTOR_URL (and _USER, _PASSWORD),
 * for example from a {@code pgvector/pgvector:pg16} container. Runs in the extended pass ({@code -Pextended}); skipped, and so unproven, without it. V7.5.
 */
@Tag("integration")
class PgvectorIntegrationTest {

    @Tag("SB-V7.5")
    @Test
    void addSearchAndDeleteWorkAgainstARealDatabase() {
        String url = System.getenv("LLM4J_TEST_PGVECTOR_URL");
        Assumptions.assumeTrue(url != null, "LLM4J_TEST_PGVECTOR_URL is not set");
        Fixtures.runner().withPropertyValues("llm4j.rag.store=pgvector", "llm4j.rag.pgvector.url=" + url, "llm4j.rag.pgvector.user=" + System.getenv("LLM4J_TEST_PGVECTOR_USER"),
                "llm4j.rag.pgvector.password=" + System.getenv("LLM4J_TEST_PGVECTOR_PASSWORD"), "llm4j.rag.pgvector.table=llm4j_starter_it", "llm4j.rag.pgvector.dimension=3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            VectorStore store = ctx.getBean(VectorStore.class);
            store.add("a", new float[] {1f, 0f, 0f}, Map.of("k", "v"));
            store.add("b", new float[] {0f, 1f, 0f}, Map.of());
            assertThat(store.search(new float[] {1f, 0f, 0f}, 1)).first().extracting(VectorStore.SearchResult::getId).isEqualTo("a");
            store.delete("a");
            assertThat(store.search(new float[] {1f, 0f, 0f}, 2)).extracting(VectorStore.SearchResult::getId).doesNotContain("a");
        });
    }
}
