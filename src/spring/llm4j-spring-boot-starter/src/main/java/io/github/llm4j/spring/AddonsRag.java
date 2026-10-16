package io.github.llm4j.spring;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.embedding.OnnxEmbeddingProvider;
import io.github.llm4j.agent.rag.store.PGVectorStore;
import io.github.llm4j.agent.rag.store.PineconeVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;

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
        } catch (Exception | LinkageError e) {
            String missing = e instanceof LinkageError ? " (a library it needs is not on the class path: " + e.getMessage() + ")" : "";
            throw new Llm4jConfigurationException("llm4j.rag.embedding=" + kind + " could not load " + r.model() + ": " + e.getMessage() + missing,
                    "Check llm4j.rag.model" + (kind.equals("djl") ? ", and that ai.djl:api, ai.djl.huggingface:tokenizers and an engine are dependencies." : "."));
        }
    }

    static VectorStore pgvector(PgvectorSettings s) {
        return s.fromSecret()
                ? PGVectorStore.withSecret(s.url(), s.user(), s.password(), s.table(), s.dimension())
                : new PGVectorStore(s.url(), s.user(), s.password() == null ? null : s.password().resolve(), s.table(), s.dimension());
    }

    static VectorStore pinecone(PineconeSettings s) {
        return s.fromSecret() ? PineconeVectorStore.withSecret(s.key(), s.index(), s.namespace()) : new PineconeVectorStore(s.key().resolve(), s.index(), s.namespace());
    }
}
