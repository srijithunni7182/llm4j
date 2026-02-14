package io.github.llm4j.agent.rag;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.rag.document.Document;
import io.github.llm4j.agent.rag.document.DocumentChunk;
import io.github.llm4j.agent.rag.embedding.EmbeddingProvider;
import io.github.llm4j.agent.rag.store.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval-Augmented Generation) agent that retrieves relevant context
 * from a vector store before generating responses.
 */
public class RAGAgent {

    private static final Logger logger = LoggerFactory.getLogger(RAGAgent.class);

    private final ReActAgent agent;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;
    private final int topK;
    private final boolean includeMetadata;

    private RAGAgent(Builder builder) {
        this.agent = Objects.requireNonNull(builder.agent, "agent cannot be null");
        this.vectorStore = Objects.requireNonNull(builder.vectorStore, "vectorStore cannot be null");
        this.embeddingProvider = Objects.requireNonNull(builder.embeddingProvider,
                "embeddingProvider cannot be null");
        this.topK = builder.topK;
        this.includeMetadata = builder.includeMetadata;
    }

    /**
     * Runs the RAG agent with the given question.
     * Retrieves relevant context and augments the question before passing to the
     * agent.
     *
     * @param question the input question
     * @return the agent result
     */
    public AgentResult run(String question) {
        Objects.requireNonNull(question, "question cannot be null");

        logger.info("RAG Agent processing question: {}", question);

        // 1. Generate embedding for the question
        float[] queryEmbedding = embeddingProvider.embed(question);
        logger.debug("Generated query embedding with {} dimensions", queryEmbedding.length);

        // 2. Retrieve relevant context from vector store
        List<VectorStore.SearchResult> results = vectorStore.search(queryEmbedding, topK);
        logger.info("Retrieved {} relevant chunks", results.size());

        // 3. Build augmented prompt with context
        String augmentedQuestion = buildAugmentedPrompt(question, results);
        logger.debug("Augmented question length: {} characters", augmentedQuestion.length());

        // 4. Run the agent with augmented context
        return agent.run(augmentedQuestion);
    }

    /**
     * Adds a document to the RAG system by chunking and embedding it.
     *
     * @param document the document to add
     */
    public void addDocument(Document document) {
        Objects.requireNonNull(document, "document cannot be null");

        logger.info("Adding document: {}", document.getId());

        List<DocumentChunk> chunks = document.getChunks();
        if (chunks.isEmpty()) {
            logger.warn("Document {} has no chunks", document.getId());
            return;
        }

        // Generate embeddings for all chunks
        List<String> chunkContents = chunks.stream()
                .map(DocumentChunk::getContent)
                .collect(Collectors.toList());

        List<float[]> embeddings = embeddingProvider.embedBatch(chunkContents);

        // Add chunks to vector store
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] embedding = embeddings.get(i);

            // Store chunk content in metadata
            chunk.getMetadata().put("content", chunk.getContent());
            chunk.getMetadata().put("documentId", document.getId());

            vectorStore.add(chunk.getId(), embedding, chunk.getMetadata());
        }

        logger.info("Added {} chunks from document {}", chunks.size(), document.getId());
    }

    /**
     * Builds an augmented prompt by prepending retrieved context to the question.
     *
     * @param question the original question
     * @param results  the search results
     * @return augmented prompt
     */
    private String buildAugmentedPrompt(String question, List<VectorStore.SearchResult> results) {
        if (results.isEmpty()) {
            return question;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Use the following context to answer the question. ");
        prompt.append("If the context doesn't contain relevant information, say so.\n\n");
        prompt.append("Context:\n");
        prompt.append("---\n");

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);
            String content = (String) result.getMetadata().get("content");

            if (content != null) {
                prompt.append(String.format("[%d] %s\n", i + 1, content));

                if (includeMetadata) {
                    String documentId = (String) result.getMetadata().get("documentId");
                    if (documentId != null) {
                        prompt.append(String.format("    (Source: %s, Relevance: %.2f)\n",
                                documentId, result.getSimilarity()));
                    }
                }
                prompt.append("\n");
            }
        }

        prompt.append("---\n\n");
        prompt.append("Question: ").append(question);

        return prompt.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ReActAgent agent;
        private VectorStore vectorStore;
        private EmbeddingProvider embeddingProvider;
        private int topK = 3;
        private boolean includeMetadata = false;

        private Builder() {
        }

        public Builder agent(ReActAgent agent) {
            this.agent = agent;
            return this;
        }

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder embeddingProvider(EmbeddingProvider embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
            return this;
        }

        public Builder topK(int topK) {
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive");
            }
            this.topK = topK;
            return this;
        }

        public Builder includeMetadata(boolean includeMetadata) {
            this.includeMetadata = includeMetadata;
            return this;
        }

        public RAGAgent build() {
            return new RAGAgent(this);
        }
    }
}
