package io.github.llm4j.loom.host;

import java.util.Map;

/**
 * Told how a run ended, whoever started it: a blocking {@link LoomHost#run}, a background {@link
 * LoomHost#start}, or a run carried on by an answer, a timer or {@link LoomHost#resumeAsync}. A
 * background or resumed run hands nothing back to its caller, so this is how an application
 * learns its result.
 *
 * <p>It is told once the run's record is saved and the run released, so the record it is given is
 * the one {@link LoomHost#run(String)} returns. A run that suspends is an ending too (its status
 * is {@link RunRecord.Status#SUSPENDED}); it is told again when it is resumed and ends.
 */
@FunctionalInterface
public interface RunListener {

    /**
     * @param record the run as the host recorded it: its status, and for a suspended, stopped or
     *     failed run the reason
     * @param variables the workflow's values at the end, unmodifiable; empty when the run never
     *     got as far as having any
     */
    void ended(RunRecord record, Map<String, Object> variables);
}
