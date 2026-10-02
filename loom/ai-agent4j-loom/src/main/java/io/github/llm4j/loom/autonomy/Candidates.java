package io.github.llm4j.loom.autonomy;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Path;

/** Builds the executor a replay runs a case in: the candidate script, over the given journal, with models and tools as the host provides them. */
public interface Candidates {

    /**
     * @param loaded  the candidate script, loaded once for the whole replay
     * @param script  the candidate script file
     * @param baseDir where the script's relative files are found (a copy with a replaced policy file, for a policy replay)
     * @param run     the case's run (its inputs, and the script it ran under)
     * @param journal the overlay the executor must use as its journal
     */
    HarnessExecutor create(LoomScript loaded, Path script, Path baseDir, CaseSource.OpenedRun run, RunJournal journal) throws Exception;
}
