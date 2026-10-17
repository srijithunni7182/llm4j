package io.github.llm4j.loom.ast;

/** {@code return value}: sets the workflow's {@code result} (what a {@code call} hands back) and ends the workflow there. */
public class ReturnStmt extends LocatedStatement {
    private final ValueExpr value;

    public ReturnStmt(ValueExpr value) {
        this.value = value;
    }

    public ValueExpr getValue() {
        return value;
    }
}
