package io.github.llm4j.loom.execution;

import java.util.Map;

/** Test access to the package-private text extraction of the automatic tool-call check. */
public final class PolicyToolHookAccess {
    private PolicyToolHookAccess() {}

    public static String textOf(Map<String, Object> arguments) {
        return PolicyToolHook.textOf(arguments);
    }
}
