package io.github.llm4j.loom.ast;

import java.util.List;

/**
 * {@code for each item in list.path { ... }} — runs the body once per element, with {@code item}
 * bound to the element. {@code parallel for each} runs the iterations concurrently.
 */
public class ForEachStmt implements Statement {
    private final String itemName;
    private final String collectionPath;
    private final List<Statement> body;
    private final boolean parallel;

    public ForEachStmt(String itemName, String collectionPath, List<Statement> body, boolean parallel) {
        this.itemName = itemName;
        this.collectionPath = collectionPath;
        this.body = body;
        this.parallel = parallel;
    }

    public String getItemName() { return itemName; }
    public String getCollectionPath() { return collectionPath; }
    public List<Statement> getBody() { return body; }
    public boolean isParallel() { return parallel; }
}
