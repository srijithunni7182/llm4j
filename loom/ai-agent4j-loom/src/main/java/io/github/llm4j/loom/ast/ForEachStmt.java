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

    private BudgetDef budget;
    /** This statement's own budget ({@code budget N tokens}), or null. */
    public BudgetDef getBudget() { return budget; }
    public void setBudget(BudgetDef budget) { this.budget = budget; }

    private final List<Statement> onExhausted = new java.util.ArrayList<>();
    /** Runs when the for-each's budget runs out before every item was processed. */
    public List<Statement> getOnExhausted() { return onExhausted; }
}
