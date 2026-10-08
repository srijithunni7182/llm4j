package io.github.llm4j.loom.ast;

public interface Statement extends Node {

    /** The 1-based source line the statement starts on, or 0 when it was not built from a script. */
    default int getLine() {
        return 0;
    }

    /** Records where the statement starts; statements that do not keep a line ignore it. */
    default void setLine(int line) {
    }
}
