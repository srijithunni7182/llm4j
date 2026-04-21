package io.github.llm4j.nirmaan.config;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;
import io.github.llm4j.agent.rag.store.PGVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

@Configuration
public class RAGConfiguration {

    private static final Logger log = Logger.getLogger(RAGConfiguration.class.getName());

    @Value("${rag.onnx.model.path:models/onnx_minilm/model.onnx}")
    private String modelPathStr;

    @Value("${rag.onnx.tokenizer.path:models/onnx_minilm/tokenizer.json}")
    private String tokenizerPathStr;

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/nirmaan_db}")
    private String dbUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:postgres}")
    private String dbPassword;

    @Bean
    public EmbeddingProvider embeddingProvider() {
        try {
            Path modelPath = Paths.get(modelPathStr);
            Path tokenizerPath = Paths.get(tokenizerPathStr);

            if (!Files.exists(modelPath) || !Files.exists(tokenizerPath)) {
                log.warning("ONNX Model files not found at " + modelPath + ". RAG Embeddings disabled.");
                return null;
            }

            return new OnnxEmbeddingProvider(modelPath, tokenizerPath);
        } catch (Exception e) {
            log.warning("Failed to initialize OnnxEmbeddingProvider: " + e.getMessage());
            return null;
        }
    }

    @Bean
    public VectorStore vectorStore() {
        try {
            // Check for simple connection availability before creating bean?
            // Or just create it and let it fail if DB down?
            // Since we want graceful degradation, we might return null if DB is
            // unreachable.
            // But checking connection here makes startup slow.
            // Let's assume if Onnx is present, user wants RAG.
            // But safely, let's catch initialization errors.

            // Using default 384 dimensions for MiniLM
            return new PGVectorStore(dbUrl, dbUser, dbPassword, "nirmaan_memory", 384);
        } catch (Exception e) {
            log.warning("Failed to initialize PGVectorStore: " + e.getMessage());
            return null;
        }
    }
}
