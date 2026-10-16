package io.github.llm4j.loom.ast;

/** {@code set name = value}: gives a workflow variable a value (text, number, true/false, a copy of another variable, or an object). */
public class SetStmt extends LocatedStatement {
    private final String name;
    private final ValueExpr value;

    public SetStmt(String name, ValueExpr value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public ValueExpr getValue() {
        return value;
    }
}
