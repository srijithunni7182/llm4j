package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.tools.CanonicalArgs;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Asks a person before an approved-only tool call runs ({@code approve: [..]} on an agent). The answer is
 * journaled under the step, the tool and a hash of its exact arguments, so a resumed run never asks twice
 * for the same call — and never lets an answer approve a call nobody saw. When the human interface pauses
 * the run ({@link io.github.llm4j.loom.runtime.RunSuspended}), no thread waits for the answer.
 */
final class ApprovalGate {

    private final HarnessExecutor executor;

    ApprovalGate(HarnessExecutor executor) {
        this.executor = executor;
    }

    boolean approve(String agent, String tool, Map<String, Object> args, String thought) {
        String key = key(executor.identityStep(), tool, args);
        RunJournal journal = executor.getJournal();
        String masked = executor.maskPii(String.valueOf(args));
        audit("approval_requested", agent, tool, masked);
        String answer = journal.get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            String question = "Agent " + agent + " wants to call " + tool + " with " + args
                    + (thought == null || thought.isBlank() ? "" : ". Reason: " + thought.strip()) + ". Approve? yes/no";
            answer = executor.humanInterface().promptHuman(key, question, new io.github.llm4j.loom.runtime.HumanInterface.Hints(io.github.llm4j.loom.runtime.HumanInterface.Hints.Kind.APPROVAL, java.util.List.of("yes", "no"), null)); // may pause the run
            journal.put(key, new RunJournal.Entry("human", answer));
        }
        boolean yes = yes(answer);
        audit(yes ? "approval_granted" : "approval_rejected", agent, tool, masked);
        return yes;
    }

    /** Asks before a {@code run} step's task runs (a task with {@code requiresApproval}); journaled like a tool call's approval. */
    boolean approveTask(String task, Map<String, Object> args) {
        String key = key(executor.identityStep(), task, args);
        RunJournal journal = executor.getJournal();
        String masked = executor.maskPii(String.valueOf(args));
        audit("approval_requested", null, task, masked);
        String answer = journal.get(key).map(e -> String.valueOf(e.value())).orElse(null);
        if (answer == null) {
            String question = "Task " + task + " wants to run with " + args + ". Approve? yes/no";
            answer = executor.humanInterface().promptHuman(key, question, new io.github.llm4j.loom.runtime.HumanInterface.Hints(io.github.llm4j.loom.runtime.HumanInterface.Hints.Kind.APPROVAL, java.util.List.of("yes", "no"), null)); // may pause the run
            journal.put(key, new RunJournal.Entry("human", answer));
        }
        boolean yes = yes(answer);
        audit(yes ? "approval_granted" : "approval_rejected", null, task, masked);
        return yes;
    }

    static boolean yes(String answer) {
        String a = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        return a.equals("yes") || a.equals("y") || a.equals("ok") || a.equals("approve") || a.equals("true");
    }

    /** {@code <step>#approve:<tool>:<12 hex of sha-256(tool + canonical json(args))>}. */
    static String key(String step, String tool, Map<String, Object> args) {
        return step + "#approve:" + tool + ":" + CanonicalArgs.hash12(tool, args);
    }

    private void audit(String event, String agent, String tool, String maskedArgs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", agent);
        data.put("tool", tool);
        data.put("step", executor.currentStep());
        data.put("args", maskedArgs);
        executor.audit(event, data);
        executor.trace(TraceEvent.APPROVAL, agent, event.replace('_', ' ') + ": " + tool + " " + maskedArgs, data);
    }
}
