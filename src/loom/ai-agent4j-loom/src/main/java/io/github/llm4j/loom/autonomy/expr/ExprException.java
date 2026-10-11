package io.github.llm4j.loom.autonomy.expr;

/** A ladder expression that cannot be read or does not check. The message names the column and says what was expected. */
public class ExprException extends RuntimeException {

    private final int column;

    public ExprException(String message, int column) {
        super(message);
        this.column = column;
    }

    /** The 1-based column in the expression text, or 0 when not known. */
    public int column() {
        return column;
    }
}
