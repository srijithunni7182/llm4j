package io.github.llm4j.spring;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomRun;
import io.github.llm4j.loom.host.AnswerResult;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRecord;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.secret.InvokerCredentials;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the configured script's workflows: a thin face on a {@link LoomHost}, which does the work
 * and needs no Spring. Start a run for a person, wait for it or not, answer the questions it asks,
 * and resume what was suspended, even after the application restarted (when {@code
 * llm4j.loom.persistence} is set).
 *
 * <pre>{@code
 * RunOutcome out = loom.run(RunRequest.of("Support", Map.of("ticket", text)).as(credentialsOfTheUser));
 * if (out instanceof RunOutcome.Suspended s) loom.answer(s.runId(), s.stepId(), "yes", "asha");
 * }</pre>
 */
public final class LoomRuntime {

    private final LoomHost host;
    private final LoomScript script;
    private final HostRunner runner;

    LoomRuntime(LoomHost host, HostRunner runner) {
        this.host = host;
        this.script = host.script();
        this.runner = runner;
    }

    /** The host underneath, for everything this class does not name. */
    public LoomHost host() {
        return host;
    }

    public LoomScript script() {
        return script;
    }

    /** Runs a workflow on the calling thread. @see LoomHost#run */
    public RunOutcome run(RunRequest request) {
        return host.run(request);
    }

    public RunOutcome run(String workflow, Map<String, String> inputs) {
        return host.run(RunRequest.of(workflow, inputs));
    }

    /**
     * Starts a workflow in the background and returns at once; @throws TooManyRuns beyond {@code
     * llm4j.loom.max-concurrent-runs}
     */
    public LoomRun start(RunRequest request) {
        return host.start(request);
    }

    public LoomRun start(String workflow, Map<String, String> inputs) {
        return host.start(RunRequest.of(workflow, inputs));
    }

    public RunOutcome resume(String runId, InvokerCredentials invoker) {
        return host.resume(runId, invoker);
    }

    public LoomRun resumeAsync(String runId, InvokerCredentials invoker) {
        return host.resumeAsync(runId, invoker);
    }

    /** Gives a run waiting at a question its answer; the run carries on. */
    public AnswerResult answer(String runId, String stepId, String text, String by) {
        return host.answer(runId, stepId, text, by);
    }

    public List<RunRecord> runs() {
        return host.runs();
    }

    public Optional<RunRecord> run(String runId) {
        return host.run(runId);
    }

    /**
     * Asks the runs in progress to wrap up, waits up to {@code grace}, then suspends what is left
     * (a run with a store resumes later) or cancels it.
     */
    public List<String> shutdown(java.time.Duration grace) {
        return host.shutdown(grace);
    }

    void close() {
        host.close();
        runner.close();
    }
}
