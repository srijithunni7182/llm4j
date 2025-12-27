package io.github.llm4j.agent.rag.document;

import java.util.*;

/**
 * Represents a chunk of a document with its content, position, and optional
 * embedding.
 */
public class DocumentChunk {

    private final String id;
    private final String documentId;
    private final String content;
    private final int startIndex;
    private final int endIndex;
    private final Map<String, Object> metadata;
    private float[] embedding;

    private DocumentChunk(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.documentId = Objects.requireNonNull(builder.documentId, "documentId cannot be null");
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.startIndex = builder.startIndex;
        this.endIndex = builder.endIndex;
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.embedding = builder.embedding;
    }

    public String getId() {
        return id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getContent() {
        return content;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public boolean hasEmbedding() {
        return embedding != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String documentId;
        private String content;
        private int startIndex;
        private int endIndex;
        private Map<String, Object> metadata = new HashMap<>();
        private float[] embedding;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder documentId(String documentId) {
            this.documentId = documentId;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder startIndex(int startIndex) {
            this.startIndex = startIndex;
            return this;
        }

        public Builder endIndex(int endIndex) {
            this.endIndex = endIndex;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder embedding(float[] embedding) {
            this.embedding = embedding;
            return this;
        }

        public DocumentChunk build() {
            return new DocumentChunk(this);
        }
    }

    @Override
    public String toString() {
        return "DocumentChunk{" +
                "id='" + id + '\'' +
                ", documentId='" + documentId + '\'' +
                ", contentLength=" + content.length() +
                ", hasEmbedding=" + hasEmbedding() +
                '}';
    }
}
