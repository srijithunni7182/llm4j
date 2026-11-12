package io.github.llm4j.loom.children;

import io.github.llm4j.loom.ast.LoomScript;

/** Lets tests outside this package parse a script the way the children tests do. */
public final class StartParseTestAccess {
    private StartParseTestAccess() {}

    /** The script in {@code source}. */
    public static LoomScript parse(String source) {
        return StartParseTest.parse(source);
    }
}
