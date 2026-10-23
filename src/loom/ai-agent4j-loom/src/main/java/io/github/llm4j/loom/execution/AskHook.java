package io.github.llm4j.loom.execution;

/**
 * Told what each step asks of a model, classifier or task, and asked whether an answer is already
 * known. Used to explain a run (what was asked, from the journal) and to change one (a different
 * answer, or the recorded one when nothing it depends on has changed).
 */
@FunctionalInterface
public interface AskHook {

    /**
     * @param recorded true when the journal already holds this step's answer (the call is a replay)
     * @return for a step with no recorded answer, the value to use as its answer instead of asking
     *     (a text or map, as the journal would hold it), or null to ask as usual; ignored when
     *     {@code recorded}
     */
    Object ask(Ask ask, boolean recorded);
}
