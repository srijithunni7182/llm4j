package io.github.llm4j.loom.execution;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.UseStmt;
import io.github.llm4j.loom.runtime.RunJournal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs {@code use Tool(name = value) -> result} steps (docs/specs/27-boundary-approvals, R1, R2): a
 * declared tool called with named arguments and no model in between. It is the same tool object an
 * agent would be given, so the same reach checks, secrets and effect protocol apply; this class adds
 * only what a written step needs: the arguments resolved like a {@code run}'s, an optional question
 * to a person, a journaled result so a resumed run does not call twice, and retry, backoff and
 * timeout.
 */
final class UseRunner {

    private final HarnessExecutor executor;

    UseRunner(HarnessExecutor executor) {
        this.executor = executor;
    }

    void run(UseStmt stmt) {
        String name = stmt.getTool();
        executor.requireAllowed(name);
        String stepId = executor.currentStep();
        RunJournal journal = executor.journal();

        var recorded = journal.get(stepId).filter(e -> !"retry".equals(e.kind()));
        if (recorded.isPresent()) {
            replay(stmt, recorded.get());
            return;
        }

        Tool tool;
        HarnessExecutor.Tracked<Map<String, Object>> origin;
        try {
            tool = executor.toolNamed(name);
            origin = executor.tracked(() -> arguments(stmt));
        } catch (StepFailure bad) {
            fail(stmt, stepId, bad.getMessage(), bad, 0);
            return;
        }
        Map<String, Object> args = origin.value();
        String mismatch = parameterProblem(tool, name, args);
        if (mismatch != null) {
            fail(stmt, stepId, mismatch, null, 0);
            return;
        }

        // What a tool that changes things is handed leaves the workflow with it (phase 26, R6.3).
        if (executor.toolEffect(name) == TaskEffect.CHANGES)
            executor.guardSink("use " + name, args, origin);

        if (stmt.isApprove()
                && !executor.approvals().approveStep("Tool", name, args, stmt.getApproveHeading())) {
            journal.put(stepId, new RunJournal.Entry("declined", name));
            if (stmt.getOnDeclined().isEmpty()) {
                fail(stmt, stepId, "not approved by a person: " + name + " was not run", null, 0);
                return;
            }
            executor.runFailureHandler(stmt.getOnDeclined(), "not approved");
            return;
        }

        long timeout = stmt.getTimeoutMillis();
        int maxAttempts = stmt.getRetryCount() + 1;
        Throwable last = null;
        int tried = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            tried++;
            long started = executor.clock().millis();
            try {
                if (attempt > 0 && stmt.getBackoffMillis() > 0) {
                    executor.sleeper()
                            .sleep(Duration.ofMillis(stmt.getBackoffMillis() << Math.min(attempt - 1, 10)));
                }
                String result =
                        timeout > 0
                                ? executor.callWithTimeout(
                                        () -> call(tool, args, stepId), timeout, "use " + name)
                                : call(tool, args, stepId);
                done(stmt, stepId, result, tried, started);
                return;
            } catch (io.github.llm4j.agent.AgentInterrupt pause) {
                throw pause; // waiting for a person is not a failure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while calling " + name, e);
            } catch (Exception e) {
                last = e;
            }
        }
        String message = last == null || last.getMessage() == null ? "unknown error" : last.getMessage();
        fail(stmt, stepId, message, last, tried);
    }

    /**
     * The arguments checked against the tool's declared parameters: an unknown name or a missing
     * required one is named with the parameters the tool takes. A tool that declares none (a
     * permissive schema) accepts any.
     */
    static String parameterProblem(Tool tool, String name, Map<String, Object> args) {
        Map<String, Object> schema = tool.getParametersSchema();
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> props) || props.isEmpty())
            return null;
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        props.keySet().forEach(k -> known.add(String.valueOf(k)));
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (String given : args.keySet()) if (!known.contains(given)) unknown.add(given);
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (schema.get("required") instanceof Iterable<?> req)
            for (Object r : req) if (!args.containsKey(String.valueOf(r))) missing.add(String.valueOf(r));
        if (unknown.isEmpty() && missing.isEmpty()) return null;
        return "use "
                + name
                + ": "
                + (unknown.isEmpty() ? "" : "unknown argument " + String.join(", ", unknown) + "; ")
                + (missing.isEmpty() ? "" : "missing required argument " + String.join(", ", missing) + "; ")
                + name
                + " takes "
                + String.join(", ", known);
    }

    private String call(Tool tool, Map<String, Object> args, String stepId) {
        return executor.atStep(
                stepId + "#use",
                () -> {
                    try {
                        return tool.execute(args);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
    }

    /** Resolved like a {@code run}'s: a variable keeps its type, a text has its {@code {…}} filled. */
    private Map<String, Object> arguments(UseStmt stmt) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RunStmt.Arg a : stmt.getArgs()) {
            switch (a.kind()) {
                case STRING -> out.put(a.name(), executor.resolve(a.text()));
                case BOOLEAN -> out.put(a.name(), Boolean.valueOf(a.text()));
                case NUMBER -> out.put(a.name(), number(a.text()));
                case REFERENCE -> {
                    String head =
                            a.text().contains(".")
                                    ? a.text().substring(0, a.text().indexOf('.'))
                                    : a.text();
                    Object value =
                            executor.view().getAll().containsKey(head)
                                    ? io.github.llm4j.loom.runtime.ConditionEvaluator.resolvePath(
                                            a.text(), executor.view())
                                    : null;
                    if (value == null)
                        throw new StepFailure(
                                "argument "
                                        + a.name()
                                        + " refers to "
                                        + a.text()
                                        + ", which has no value, so "
                                        + stmt.getTool()
                                        + " was not called",
                                null);
                    executor.touch(a.text());
                    out.put(a.name(), value);
                }
            }
        }
        return out;
    }

    private static Number number(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException notInteger) {
            return Double.valueOf(text);
        }
    }

    // ---- outcomes

    private void done(UseStmt stmt, String stepId, String result, int attempts, long started) {
        Object value = parsed(result);
        executor.journal().put(stepId, new RunJournal.Entry("use", value));
        if (stmt.getVariableName() != null)
            executor.bind(
                    executor.resolveVariableName(stmt.getVariableName()),
                    value,
                    Provenance.NOT_MODEL);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", stmt.getTool());
        data.put("step", stepId);
        data.put("outcome", "OK");
        data.put("attempts", attempts);
        data.put("duration_ms", executor.clock().millis() - started);
        data.put("result_chars", result == null ? 0 : result.length());
        io.github.llm4j.loom.security.ToolReach reach = executor.reachOf(stmt.getTool());
        data.put("reach", reach.legs());
        data.put("reach_known", reach.known());
        data.put("by", "use");
        executor.trace(TraceEvent.TOOL_CALL, null, stmt.getTool() + " ok (use)", data);
    }

    /** A tool that returns JSON gives an object; anything else stays text. */
    private static Object parsed(String result) {
        if (result == null) return "";
        String t = result.strip();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            Object read = PayloadText.parseJson(t);
            if (!(read instanceof String)) return read;
        }
        return result;
    }

    private void replay(UseStmt stmt, RunJournal.Entry entry) {
        switch (entry.kind()) {
            case "failed" -> fail(stmt, executor.currentStep(), String.valueOf(entry.value()), null, 0, false);
            case "declined" -> {
                if (stmt.getOnDeclined().isEmpty())
                    fail(stmt, executor.currentStep(), "not approved by a person: " + stmt.getTool() + " was not run", null, 0, false);
                else executor.runFailureHandler(stmt.getOnDeclined(), "not approved");
            }
            default -> {
                if (stmt.getVariableName() != null)
                    executor.bind(
                            executor.resolveVariableName(stmt.getVariableName()),
                            entry.value(),
                            Provenance.NOT_MODEL);
            }
        }
    }

    private void fail(UseStmt stmt, String stepId, String message, Throwable cause, int attempts) {
        fail(stmt, stepId, message, cause, attempts, true);
    }

    private void fail(
            UseStmt stmt, String stepId, String message, Throwable cause, int attempts, boolean record) {
        executor.traceFailure(
                "use", stmt.getTool(), message, cause, null, attempts, !stmt.getOnFailure().isEmpty());
        if (stmt.getOnFailure().isEmpty())
            throw new RuntimeException("use " + stmt.getTool() + " failed: " + message, cause);
        if (record) executor.journal().put(stepId, new RunJournal.Entry("failed", message));
        executor.runFailureHandler(stmt.getOnFailure(), message);
    }
}
