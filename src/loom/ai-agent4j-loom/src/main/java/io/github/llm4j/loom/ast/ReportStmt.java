package io.github.llm4j.loom.ast;

/**
 * {@code report { field: value, ... }}: a workflow tells whoever watches it where it is (phase 34b,
 * D6). The fields are those the workflow declared with {@code progress { }}. The value is flat: a
 * text, a number, true or false, or a variable.
 */
public final class ReportStmt extends LocatedStatement {
    private final ValueExpr.Obj value;

    public ReportStmt(ValueExpr.Obj value) {
        this.value = value;
    }

    /** The fields reported, in the order written. */
    public ValueExpr.Obj getValue() {
        return value;
    }
}
