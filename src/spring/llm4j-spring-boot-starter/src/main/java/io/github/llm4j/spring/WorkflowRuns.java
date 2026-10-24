package io.github.llm4j.spring;

import io.github.llm4j.loom.host.AnswerResult;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.List;
import java.util.Optional;

/** What an application does with runs after they start: answer, resume, look. */
public final class WorkflowRuns {

    private final LoomRuntime runtime;

    WorkflowRuns(LoomRuntime runtime) {
        this.runtime = runtime;
    }

    /** Gives a run waiting for a person its answer; the run carries on. */
    public AnswerResult answer(String runId, String stepId, String text, String by) {
        return runtime.answer(runId, stepId, text, by);
    }

    /** Carries on a suspended run on the calling thread. */
    public RunOutcome resume(String runId) {
        return runtime.resume(runId, null);
    }

    public RunOutcome resume(String runId, InvokerCredentials invoker) {
        return runtime.resume(runId, invoker);
    }

    public Optional<RunRecord> status(String runId) {
        return runtime.run(runId);
    }

    public List<RunRecord> runs() {
        return runtime.runs();
    }
}
