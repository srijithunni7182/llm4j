package io.github.llm4j.loom.knowledge;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;

/** Creates the embedding model a knowledge base names, e.g. {@code gemini/text-embedding-004}. */
@FunctionalInterface
public interface EmbeddingFactory {

    EmbeddingProvider create(String model) throws Exception;

    /** A problem with a model name, found without creating anything (for load-time checks); null if fine. */
    default String problem(String model) {
        return null;
    }
}
