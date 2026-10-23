package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one case: loads the script afresh, gives every agent and classifier a model name of its own,
 * plugs in the scripted collaborators, runs the workflow and returns what happened. Each case has
 * its own executor, so cases share nothing.
 */
final class CaseRunner {

    private CaseRunner() {}

    /** What one case did. */
    record Outcome(
            String status,
            Object result,
            Map<String, Object> variables,
            Recorder recorder,
            Map<String, String> modelsUsed,
            String error,
            long millis) {}

    static Outcome run(Path script, String workflow, TestFile.Case c) {
        long start = System.nanoTime();
        Recorder recorder = new Recorder();
        Map<String, String> modelsUsed = new LinkedHashMap<>();
        HarnessExecutor executor = null;
        String error = null;
        try {
            LoomScript fresh = new LoomLoader().load(script.toString());
            Map<String, JsonNode> scripts = new LinkedHashMap<>();
            JsonNode models = c.get("models");
            if (models != null && models.isObject())
                for (Iterator<Map.Entry<String, JsonNode>> it = models.fields(); it.hasNext(); ) {
                    var e = it.next();
                    scripts.put(e.getKey(), e.getValue());
                }
            for (AgentDef a : fresh.getAgents()) {
                modelsUsed.put(a.getName(), a.getModel());
                a.setModel(ScriptedModels.PREFIX + a.getName());
            }
            for (IntentClassifierDef k : fresh.getIntentClassifiers())
                k.setModel(ScriptedClassifiers.PREFIX + k.getName(), k.getModelLine());
            boolean lenient = c.has("lenient") && c.get("lenient").asBoolean();
            boolean repeatLast = c.has("repeat") && "last".equals(c.get("repeat").asText());
            executor =
                    new HarnessExecutor(
                            fresh,
                            new ToolRegistry(),
                            new ScriptedModels(scripts, lenient, repeatLast, recorder));
            executor.setClassifierFactory(new ScriptedClassifiers(scripts, recorder));
            executor.setHumanInterface(
                    question -> {
                        String message =
                                "a person was asked \""
                                        + Values.cut(question.replace('\n', ' '), 120, false)
                                        + "\" and the case has no answer for it";
                        recorder.violation(message);
                        throw new IllegalStateException(message);
                    });
            executor.setBaseDir(script.toAbsolutePath().getParent());
            executor.setEnvLookup(TestRunner::standIn);
            executor.setPromptCatalog(PromptSupport.catalog(fresh, script, null));
            executor.addTraceListener(recorder);
            executor.initialize();
            Map<String, String> input = new LinkedHashMap<>();
            JsonNode in = c.get("input");
            if (in != null && in.isObject())
                in.fields().forEachRemaining(e -> input.put(e.getKey(), text(e.getValue())));
            try {
                executor.executeWorkflow(workflow, input);
            } catch (RuntimeException e) {
                error = message(e);
            }
            Map<String, Object> vars = new LinkedHashMap<>(executor.getContext().getAll());
            String status = recorder.status();
            if (status.isEmpty()) status = error != null ? "failed" : "completed";
            return new Outcome(
                    status, vars.get("result"), vars, recorder, modelsUsed, error, millis(start));
        } catch (Exception e) {
            return new Outcome(
                    "failed", null, Map.of(), recorder, modelsUsed, message(e), millis(start));
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    private static String text(JsonNode n) {
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null
                && t.getCause() != t
                && (t.getMessage() == null || t.getMessage().isBlank())) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank()
                ? t.getClass().getSimpleName()
                : m.lines().findFirst().orElse("");
    }
}
