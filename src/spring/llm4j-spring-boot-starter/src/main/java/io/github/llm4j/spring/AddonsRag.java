package io.github.llm4j.spring;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;
import io.github.llm4j.agent.rag.store.PGVectorStore;
import io.github.llm4j.agent.rag.store.PineconeVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import org.springframework.core.env.Environment;

/** The parts of the RAG beans that need ai-agent4j-addons, kept apart so it is loaded only when one of them is asked for. */
final class AddonsRag {

    private AddonsRag() { }

    static EmbeddingProvider embedding(String kind, Llm4jProperties.Rag r) {
        try {
            if (r.model() == null) throw new Llm4jConfigurationException("llm4j.rag.embedding=" + kind + " needs llm4j.rag.model", "Give the path (onnx) or URL (djl) of the model.");
            if (kind.equals("onnx")) {
                if (r.tokenizer() == null) throw new Llm4jConfigurationException("llm4j.rag.embedding=onnx needs llm4j.rag.tokenizer", "Give the path of the tokenizer.json.");
                return new OnnxEmbeddingProvider(r.model(), r.tokenizer());
            }
            // DJL is optional even for the addons, so it is not named at compile time
            return (EmbeddingProvider) Class.forName("io.github.llm4j.agent.rag.embedding.DjlEmbeddingProvider").getConstructor(String.class).newInstance(r.model());
        } catch (Llm4jConfigurationException e) {
            throw e;
        } catch (Exception e) {
            throw new Llm4jConfigurationException("llm4j.rag.embedding=" + kind + " could not load " + r.model() + ": " + e.getMessage(), "Check llm4j.rag.model.");
        }
    }

    static VectorStore pgvector(Llm4jProperties.Rag.Pgvector p, SecretStore secrets, Environment env) {
        String url = p.url() != null ? p.url() : env.getProperty("spring.datasource.url");
        String user = p.user() != null ? p.user() : env.getProperty("spring.datasource.username");
        if (url == null) throw new Llm4jConfigurationException("llm4j.rag.store=pgvector has no database", "Set llm4j.rag.pgvector.url, or spring.datasource.url.");
        if (p.dimension() == null) throw new Llm4jConfigurationException("llm4j.rag.store=pgvector needs llm4j.rag.pgvector.dimension", "Give the size of the embedding vectors.");
        SecretRef password = CredentialProperty.ref("llm4j.rag.pgvector.password", p.password(), p.passwordSecret(), secrets);
        if (password == null && env.getProperty("spring.datasource.password") != null) password = SecretRef.literal(env.getProperty("spring.datasource.password"));
        return password != null && p.passwordSecret() != null
                ? PGVectorStore.withSecret(url, user, password, p.table(), p.dimension())
                : new PGVectorStore(url, user, password == null ? null : password.resolve(), p.table(), p.dimension());
    }

    static VectorStore pinecone(Llm4jProperties.Rag.Pinecone p, SecretStore secrets) {
        SecretRef key = CredentialProperty.ref("llm4j.rag.pinecone.api-key", p.apiKey(), p.apiKeySecret(), secrets);
        if (key == null || p.index() == null) throw new Llm4jConfigurationException("llm4j.rag.store=pinecone needs an API key and an index", "Set llm4j.rag.pinecone.api-key-secret and llm4j.rag.pinecone.index.");
        return p.apiKeySecret() != null ? PineconeVectorStore.withSecret(key, p.index(), p.namespace()) : new PineconeVectorStore(key.resolve(), p.index(), p.namespace());
    }
}
