package io.github.llm4j.loom.execution;

import java.util.Optional;

/**
 * Where a test finds the scripted behaviour of a started workflow (phase 34a, F5). A host that
 * runs real children gives none, so every {@code start} runs its workflow.
 */
@FunctionalInterface
public interface ChildScripts {

    /** The scripted behaviour of a child started from {@code workflow}, or empty to run it for real. */
    Optional<ScriptedChild> forWorkflow(String workflow);
}
