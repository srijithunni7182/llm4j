package io.github.llm4j.agent.rag.document;

import java.util.*;

/**
 * Represents a document with its content and metadata.
 * Documents can be chunked for efficient retrieval and embedding.
 */
public class Document {

    private final String id;
    private final String content;
    private final Map<String, Object> metadata;
    private final List<DocumentChunk> chunks;

    private Document(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.content = Objects.requireNonNull(builder.content, "content cannot be null");
        this.metadata = Collections.unmodifiableMap(new HashMap<>(builder.metadata));
        this.chunks = new ArrayList<>(builder.chunks);
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public List<DocumentChunk> getChunks() {
        return Collections.unmodifiableList(chunks);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String content;
        private Map<String, Object> metadata = new HashMap<>();
        private List<DocumentChunk> chunks = new ArrayList<>();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
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

        public Builder chunks(List<DocumentChunk> chunks) {
            this.chunks.addAll(chunks);
            return this;
        }

        public Builder addChunk(DocumentChunk chunk) {
            this.chunks.add(chunk);
            return this;
        }

        public Document build() {
            return new Document(this);
        }
    }

    @Override
    public String toString() {
        return "Document{" +
                "id='" + id + '\'' +
                ", contentLength=" + content.length() +
                ", chunks=" + chunks.size() +
                '}';
    }
}
