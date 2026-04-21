package io.github.llm4j.loom.ast;

/**
 * AST node for a Knowledge Base / RAG source.
 */
public class KnowledgeDef implements Node {
    private final String name;
    private String type;
    private String path;
    private int chunkSize = 1000;
    private String embeddingProvider;

    public KnowledgeDef(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
}
