package io.github.llm4j.loom.knowledge;

import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.VectorStore;
import io.github.llm4j.loom.ast.KnowledgeDef;

/** A knowledge base ready to search: its definition, embedding model and filled vector store. */
public record KnowledgeIndex(KnowledgeDef def, EmbeddingProvider embeddings, VectorStore store, Stats stats) {

    /** What indexing found and did. */
    public record Stats(int files, int skipped, int chunks, int embedded, boolean rebuilt) {
        @Override
        public String toString() {
            return files + " files" + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ", " + chunks + " chunks, "
                    + embedded + " embedded" + (embedded == 0 ? " (index up to date)" : rebuilt ? " (rebuilt)" : "");
        }
    }
}
