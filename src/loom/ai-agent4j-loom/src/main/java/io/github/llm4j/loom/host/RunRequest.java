package io.github.llm4j.loom.host;

import io.github.llm4j.secret.InvokerCredentials;
import java.util.Map;

/**
 * What to run. {@code runId} names the run (a new one is made when null); {@code label} is how a
 * console or chat names it while it runs; {@code origin} is who the journal and audit say started
 * it; {@code runId} is yours to choose or left to the host (a UUID): it names a folder, so it must
 * be letters, digits and {@code . _ @ -}, and reusing the id of an earlier run replays that run's
 * journal, so an application must not let its users pick ids; {@code invoker} is the person whose
 * credentials {@code invoker.NAME} tools use (never stored).
 */
public record RunRequest(
        String workflow,
        Map<String, String> inputs,
        String runId,
        String label,
        String origin,
        InvokerCredentials invoker) {

    public RunRequest {
        if (workflow == null || workflow.isBlank())
            throw new IllegalArgumentException("a run needs a workflow name");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        if (runId != null && !RunRegistry.validId(runId))
            throw new IllegalArgumentException(
                    "a run id is 1 to 100 letters, digits, '.', '_', '@' or '-', starting with a letter or digit: "
                            + runId);
        origin = origin == null || origin.isBlank() ? "app" : origin;
    }

    public static RunRequest of(String workflow, Map<String, String> inputs) {
        return new RunRequest(workflow, inputs, null, null, null, null);
    }

    public RunRequest withRunId(String id) {
        return new RunRequest(workflow, inputs, id, label, origin, invoker);
    }

    public RunRequest labelled(String text) {
        return new RunRequest(workflow, inputs, runId, text, origin, invoker);
    }

    public RunRequest from(String who) {
        return new RunRequest(workflow, inputs, runId, label, who, invoker);
    }

    public RunRequest as(InvokerCredentials credentials) {
        return new RunRequest(workflow, inputs, runId, label, origin, credentials);
    }
}
