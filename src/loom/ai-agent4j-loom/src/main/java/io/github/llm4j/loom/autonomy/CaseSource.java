package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Finds the run a case was decided in, from the locator its ledger record carries (a run directory, or a database run id). */
public interface CaseSource {

    /**
     * The run behind a case.
     *
     * @param journal  the run's journal, opened for reading only (a replay lays an overlay over it)
     * @param workflow the workflow the run executed
     * @param inputs   the values it was started with
     * @param script   the script it ran under
     */
    record OpenedRun(RunJournal journal, String workflow, Map<String, String> inputs, Path script) { }

    /** Empty when the run is gone (retention, deleted by hand). */
    Optional<OpenedRun> open(String locator);
}
