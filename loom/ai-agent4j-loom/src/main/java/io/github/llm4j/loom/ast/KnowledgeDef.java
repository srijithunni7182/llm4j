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
    private int overlap = 100;
    private String store;          // a file path, or null for in memory
    private int topK = 4;
    private Mode mode = Mode.CONTEXT;
    private int line;

    /** How agents get the knowledge: prepended to each task, or through a search tool. */
    public enum Mode { CONTEXT, TOOL }

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
    /** Where documents come from: a file or directory ({@code source:}, or the older {@code path:}). */
    public String getSource() { return path; }
    public int getOverlap() { return overlap; }
    public void setOverlap(int overlap) { this.overlap = overlap; }
    /** The index file, or null to keep the index in memory. */
    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
}
