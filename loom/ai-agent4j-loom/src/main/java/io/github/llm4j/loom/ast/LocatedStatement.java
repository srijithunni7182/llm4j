package io.github.llm4j.loom.ast;

/** Base for statements that remember the source line they start on. */
public abstract class LocatedStatement implements Statement {
    private int line;

    @Override
    public int getLine() {
        return line;
    }

    @Override
    public void setLine(int line) {
        this.line = line;
    }
}
