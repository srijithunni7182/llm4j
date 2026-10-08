package io.github.llm4j.loom.ast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code checkpoint collected  starting with feedback = "none"}: a named point a later {@code rewind} can go back to. The optional
 * values are variables that hold from this point on (and so the defaults for values a rewind carries in).
 */
public class CheckpointStmt implements Statement {
    private final String name;
    private final Map<String, String> startingWith = new LinkedHashMap<>();
    private int line;

    public CheckpointStmt(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public Map<String, String> getStartingWith() { return startingWith; }
    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }
}
