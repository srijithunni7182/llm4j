package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.loom.trigger.Trigger;
import io.github.llm4j.loom.trigger.TriggerTarget;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fires triggers from a fresh process: resumes runs from their run directory, starts scheduled workflows
 * in {@code <store>/runs/<schedule>@<slot>}, and gives scheduled tasks to agents.
 */
final class WeaveTriggerTarget implements TriggerTarget {

    private final Path store;
    private final WeaveEnv env;

    WeaveTriggerTarget(Path store, WeaveEnv env) {
        this.store = store.toAbsolutePath().normalize();
        this.env = env;
    }

    @Override
    public Outcome fire(Trigger trigger, String runId) throws Exception {
        if (trigger.target() instanceof Trigger.ResumeRun r) {
            Path dir = Path.of(r.runId());
            if (!dir.isAbsolute() || !Files.exists(dir.resolve(RunSpec.FILE))) dir = Runs.scheduledRunDir(store, r.runId());
            env.out().println("⏯  Resuming " + r.runId());
            return outcome(Runs.execute(RunSpec.read(dir), dir, r.runId(), env));
        }
        if (trigger.target() instanceof Trigger.StartWorkflow w) {
            Path dir = Runs.scheduledRunDir(store, runId);
            RunSpec spec = new RunSpec(w.script(), null, w.workflow(), w.args(), null, null, null, null, store.toString());
            spec.write(dir);
            env.out().println("⏰ Starting " + w.workflow() + " (" + runId + ")");
            return outcome(Runs.execute(spec, dir, runId, env));
        }
        Trigger.AgentTask a = (Trigger.AgentTask) trigger.target();
        LoomScript script = new LoomLoader().load(Path.of(a.script()).toAbsolutePath().toString());
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), env.models());
        executor.setSecretStore(env.secrets());
        executor.setClock(env.clock());
        executor.setSleeper(env.sleeper());
        // the script's schedules are already stored; don't also start them in memory
        executor.setTriggerStore(new io.github.llm4j.loom.trigger.InMemoryTriggerStore());
        executor.initialize();
        try {
            env.out().println("⏰ " + a.agent() + ": " + a.task());
            executor.runAgentTask(a.agent(), a.task());
            return Outcome.done();
        } finally {
            executor.shutdown();
        }
    }

    private static Outcome outcome(Runs.Result r) {
        return switch (r.exit()) {
            case 0 -> Outcome.done();
            case 4 -> r.reason() == RunSuspended.Reason.HUMAN || r.resumeAt() == null
                    ? Outcome.human(r.message()) : Outcome.suspended(r.resumeAt(), r.message());
            default -> Outcome.failed(r.message());
        };
    }
}
