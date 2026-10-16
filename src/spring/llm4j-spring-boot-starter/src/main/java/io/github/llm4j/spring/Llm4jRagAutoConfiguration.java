package io.github.llm4j.spring;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.GeminiEmbeddingProvider;
import io.github.llm4j.agent.rag.store.FileVectorStore;
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.io.File;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** An {@link EmbeddingProvider} and a {@link VectorStore}, each only when {@code llm4j.rag.embedding} / {@code llm4j.rag.store} is set. */
@AutoConfiguration(after = Llm4jSecretsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jRagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.rag", name = "embedding")
    public EmbeddingProvider llm4jEmbeddingProvider(Llm4jProperties properties, ObjectProvider<SecretStore> secrets) {
        Llm4jProperties.Rag r = properties.rag();
        String kind = r.embedding().trim().toLowerCase(Locale.ROOT);
        switch (kind) {
            case "gemini": {
                SecretRef key = CredentialProperty.ref("llm4j.rag.api-key", r.apiKey(), r.apiKeySecret(), secrets.getIfAvailable());
                if (key == null) throw new Llm4jConfigurationException("llm4j.rag.embedding=gemini has no API key", "Set llm4j.rag.api-key-secret to the name of a secret.");
                LLMConfig config = LLMConfig.builder().apiKey(key).build();
                return r.model() == null ? new GeminiEmbeddingProvider(config) : new GeminiEmbeddingProvider(config, r.model());
            }
            case "onnx":
            case "djl":
                return AddonsRag.embedding(kind, r);
            default:
                throw new Llm4jConfigurationException("llm4j.rag.embedding=" + r.embedding() + " is not known", "Use gemini, onnx or djl, or declare an EmbeddingProvider bean.");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "llm4j.rag", name = "store")
    public VectorStore llm4jVectorStore(Llm4jProperties properties, ObjectProvider<SecretStore> secrets, Environment environment) {
        Llm4jProperties.Rag r = properties.rag();
        return switch (r.store().trim().toLowerCase(Locale.ROOT)) {
            case "memory" -> new InMemoryVectorStore();
            case "file" -> {
                if (r.file() == null) throw new Llm4jConfigurationException("llm4j.rag.store=file needs llm4j.rag.file", "Give the path of the file the vectors are kept in.");
                yield new FileVectorStore(new File(r.file()));
            }
            case "pgvector" -> AddonsRag.pgvector(r.pgvector(), secrets.getIfAvailable(), environment);
            case "pinecone" -> AddonsRag.pinecone(r.pinecone(), secrets.getIfAvailable());
            default -> throw new Llm4jConfigurationException("llm4j.rag.store=" + r.store() + " is not known", "Use memory, file, pgvector or pinecone, or declare a VectorStore bean.");
        };
    }
}
