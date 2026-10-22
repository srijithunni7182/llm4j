package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.agent.rag.store.FileVectorStore;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Embedding providers and vector stores. V7. */
class RagTest {

    @TempDir Path dir;

    @Tag("SB-V7.1")
    @Test
    void anEmbeddingProviderAndAStoreAreMadeAndWorkTogether() throws Exception {
        try (MockWebServer gemini = new MockWebServer()) {
            gemini.setDispatcher(
                    new okhttp3.mockwebserver.Dispatcher() {
                        @Override
                        public MockResponse dispatch(
                                okhttp3.mockwebserver.RecordedRequest request) {
                            String body = request.getBody().readUtf8();
                            String values = body.contains("otters") ? "[1.0, 0.0]" : "[0.0, 1.0]";
                            return new MockResponse()
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("{\"embedding\":{\"values\":" + values + "}}");
                        }
                    });
            gemini.start();
            InMemorySecretStore secrets = new InMemorySecretStore();
            secrets.put("EMBED_KEY", "k");
            Fixtures.runner()
                    .withBean(SecretStore.class, () -> secrets)
                    .withPropertyValues(
                            "llm4j.rag.embedding=gemini",
                            "llm4j.rag.api-key-secret=EMBED_KEY",
                            "llm4j.rag.base-url="
                                    + gemini.url("/v1").toString().replaceAll("/$", ""),
                            "llm4j.rag.store=memory")
                    .run(
                            ctx -> {
                                assertThat(ctx).hasNotFailed();
                                EmbeddingProvider embeddings = ctx.getBean(EmbeddingProvider.class);
                                VectorStore store = ctx.getBean(VectorStore.class);
                                assertThat(embeddings).isInstanceOf(GeminiEmbeddingProvider.class);
                                assertThat(store).isInstanceOf(InMemoryVectorStore.class);
                                store.add("otters", embeddings.embed("otters swim"), Map.of());
                                store.add("owls", embeddings.embed("owls fly"), Map.of());
                                assertThat(store.search(embeddings.embed("otters"), 1))
                                        .first()
                                        .extracting(VectorStore.SearchResult::getId)
                                        .isEqualTo("otters");
                                assertThat(gemini.takeRequest().getHeader("x-goog-api-key"))
                                        .isEqualTo("k");
                            });
            Path file = dir.resolve("vectors.json");
            Fixtures.runner()
                    .withPropertyValues("llm4j.rag.store=file", "llm4j.rag.file=" + file)
                    .run(
                            ctx -> {
                                VectorStore store = ctx.getBean(VectorStore.class);
                                assertThat(store).isInstanceOf(FileVectorStore.class);
                                store.add("a", new float[] {1f, 0f}, Map.of());
                            });
            assertThat(file).exists();
        }
    }

    @Tag("SB-V7.2")
    @Test
    void pgvectorTakesItsDatabaseFromTheApplicationsDataSourceSettings() {
        // the store connects when it is built, so what is checked here is what it would be given
        var env =
                Fixtures.environment(
                        Map.of(
                                "spring.datasource.url",
                                "jdbc:postgresql://db:5432/app",
                                "spring.datasource.username",
                                "app",
                                "spring.datasource.password",
                                "pw"));
        var own = new Llm4jProperties.Rag.Pgvector(null, null, null, null, "docs", 768);
        PgvectorSettings s = PgvectorSettings.resolve(own, null, env);
        assertThat(s.url()).isEqualTo("jdbc:postgresql://db:5432/app");
        assertThat(s.user()).isEqualTo("app");
        assertThat(s.password().resolve()).isEqualTo("pw");
        assertThat(s.table()).isEqualTo("docs");
        assertThat(s.dimension()).isEqualTo(768);
        assertThat(
                        PgvectorSettings.resolve(
                                        new Llm4jProperties.Rag.Pgvector(
                                                "jdbc:postgresql://other/db",
                                                "bob",
                                                "x",
                                                null,
                                                "t",
                                                3),
                                        null,
                                        env)
                                .url())
                .isEqualTo("jdbc:postgresql://other/db");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PgvectorSettings.resolve(own, null, Fixtures.environment(Map.of())))
                .isInstanceOfSatisfying(
                        Llm4jConfigurationException.class,
                        e -> assertThat(e.action()).contains("spring.datasource.url"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                PgvectorSettings.resolve(
                                        new Llm4jProperties.Rag.Pgvector(
                                                null, null, null, null, "docs", null),
                                        null,
                                        env))
                .isInstanceOf(Llm4jConfigurationException.class)
                .hasMessageContaining("dimension");
    }

    @Tag("SB-V7.3")
    @Test
    void passwordsAndKeysCanBeSecretsNotLiterals() {
        InMemorySecretStore secrets = new InMemorySecretStore();
        secrets.put("PG_PASSWORD", "pw");
        secrets.put("PINECONE_KEY", "pk");
        var env = Fixtures.environment(Map.of());
        PgvectorSettings viaSecret =
                PgvectorSettings.resolve(
                        new Llm4jProperties.Rag.Pgvector(
                                "jdbc:postgresql://db/app", "app", null, "PG_PASSWORD", "t", 3),
                        secrets,
                        env);
        assertThat(viaSecret.fromSecret()).isTrue();
        assertThat(viaSecret.password().resolve()).isEqualTo("pw");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                PgvectorSettings.resolve(
                                        new Llm4jProperties.Rag.Pgvector(
                                                "jdbc:postgresql://db/app",
                                                "app",
                                                "x",
                                                "PG_PASSWORD",
                                                "t",
                                                3),
                                        secrets,
                                        env))
                .isInstanceOf(Llm4jConfigurationException.class)
                .hasMessageContaining("llm4j.rag.pgvector.password")
                .hasMessageContaining("password-secret");
        PineconeSettings pinecone =
                PineconeSettings.resolve(
                        new Llm4jProperties.Rag.Pinecone(null, "PINECONE_KEY", "docs", "ns"),
                        secrets);
        assertThat(pinecone.fromSecret()).isTrue();
        assertThat(pinecone.key().resolve()).isEqualTo("pk");
        assertThat(pinecone.index()).isEqualTo("docs");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                PineconeSettings.resolve(
                                        new Llm4jProperties.Rag.Pinecone(null, null, "docs", null),
                                        secrets))
                .isInstanceOf(Llm4jConfigurationException.class);
    }

    @Tag("SB-V7.4")
    @Test
    void nothingIsMadeWithoutTheSettings() {
        Fixtures.runner()
                .run(
                        ctx ->
                                assertThat(ctx)
                                        .doesNotHaveBean(EmbeddingProvider.class)
                                        .doesNotHaveBean(VectorStore.class));
    }
}
