package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSupport;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one case: loads the script afresh, gives every agent and classifier a model name of its own,
 * replaces every tool and task with a scripted one, plugs in the scripted people and a virtual
 * clock, runs the workflow and returns what happened. Each case has its own executor and journal,
 * so cases share nothing. A run that waits for a person is suspended, and a case can continue it
 * with {@code resume:}.
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
            long millis,
            Duration elapsedVirtual) {}

    static Outcome run(Path script, String workflow, TestFile.Case c) {
        long start = System.nanoTime();
        Recorder recorder = new Recorder();
        Map<String, String> modelsUsed = new LinkedHashMap<>();
        VirtualClock clock = new VirtualClock(startOf(c));
        HarnessExecutor executor = null;
        String error = null;
        try {
            Map<String, JsonNode> models = fields(c.get("models"));
            Failures failures = new Failures(c.get("fail"));
            boolean repeatLast = c.has("repeat") && "last".equals(c.get("repeat").asText());
            boolean lenient = c.has("lenient") && c.get("lenient").asBoolean();
            List<String> humans = strings(c.get("humans"));
            ScriptedHumans people = new ScriptedHumans(humans, recorder);
            RunJournal journal = RunJournal.inMemory();
            List<String> resume = new ArrayList<>(strings(c.get("resume")));
            Map<String, String> input = inputs(c.get("input"));
            Interventions interventions = new Interventions(c.get("steer"), c.get("stop"));
            Map<String, Object> vars = Map.of();
            while (true) {
                LoomScript fresh = new LoomLoader().load(script.toString());
                for (AgentDef a : fresh.getAgents()) {
                    modelsUsed.putIfAbsent(a.getName(), a.getModel());
                    a.setModel(ScriptedModels.PREFIX + a.getName());
                }
                for (IntentClassifierDef k : fresh.getIntentClassifiers())
                    k.setModel(ScriptedClassifiers.PREFIX + k.getName(), k.getModelLine());
                ToolRegistry tools = toolsFor(fresh, c, failures, repeatLast, recorder);
                TaskRegistry tasks = tasksFor(fresh, c, failures, repeatLast, recorder);
                executor =
                        new HarnessExecutor(
                                fresh,
                                tools,
                                new ScriptedModels(
                                        models,
                                        lenient,
                                        repeatLast,
                                        recorder,
                                        failures,
                                        c.get("usage"),
                                        interventions));
                interventions.attach(executor);
                executor.setClassifierFactory(new ScriptedClassifiers(models, recorder, failures));
                executor.setTaskRegistry(tasks);
                executor.setHumanInterface(people);
                executor.setJournal(journal);
                executor.setClock(clock);
                executor.setSleeper(clock);
                executor.setBaseDir(script.toAbsolutePath().getParent());
                executor.setEnvLookup(TestRunner::standIn);
                executor.setPromptCatalog(PromptSupport.catalog(fresh, script, null));
                executor.addTraceListener(recorder);
                executor.setStepListener(recorder::step);
                executor.initialize();
                RunSuspended waiting = null;
                try {
                    executor.executeWorkflow(workflow, input);
                    error = null;
                } catch (RunSuspended s) {
                    waiting = s;
                } catch (RuntimeException e) {
                    error = message(e);
                }
                vars = new LinkedHashMap<>(executor.getContext().getAll());
                executor.shutdown();
                if (waiting != null && !resume.isEmpty()) {
                    // the run waited for a person: record the next answer as a person would, and
                    // run it again over the same journal
                    journal.answer(waiting.stepId(), resume.remove(0));
                    continue;
                }
                break;
            }
            String status = recorder.status();
            if (status.isEmpty()) status = error != null ? "failed" : "completed";
            return new Outcome(
                    status,
                    vars.get("result"),
                    vars,
                    recorder,
                    modelsUsed,
                    error,
                    millis(start),
                    clock.elapsed());
        } catch (Exception e) {
            return new Outcome(
                    "failed",
                    null,
                    Map.of(),
                    recorder,
                    modelsUsed,
                    message(e),
                    millis(start),
                    clock.elapsed());
        }
    }

    // ---- collaborators

    /** Every tool the script names, scripted. A declared tool is replaced by the scripted one. */
    private static ToolRegistry toolsFor(
            LoomScript script,
            TestFile.Case c,
            Failures failures,
            boolean repeatLast,
            Recorder recorder) {
        Map<String, ToolDef> declared = new LinkedHashMap<>();
        for (ToolDef t : script.getTools()) declared.put(t.getName(), t);
        Set<String> names = new LinkedHashSet<>(declared.keySet());
        for (AgentDef a : script.getAgents()) names.addAll(a.getTools());
        Map<String, JsonNode> scripts = fields(c.get("tools"));
        ToolRegistry registry = new ToolRegistry();
        for (String name : names)
            registry.register(
                    name,
                    ScriptedTools.of(
                            name,
                            declared.get(name),
                            scripts.get(name),
                            failures,
                            repeatLast,
                            recorder));
        script.getTools().clear(); // the scripted tools answer for them
        return registry;
    }

    /** Every task the script runs, scripted. */
    private static TaskRegistry tasksFor(
            LoomScript script,
            TestFile.Case c,
            Failures failures,
            boolean repeatLast,
            Recorder recorder) {
        Map<String, JsonNode> scripts = fields(c.get("tasks"));
        Set<String> names = new LinkedHashSet<>();
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof RunStmt r) names.add(r.getTaskName());
                    });
        TaskRegistry registry = new TaskRegistry();
        for (String name : names)
            registry.register(
                    ScriptedTasks.of(name, scripts.get(name), failures, repeatLast, recorder));
        return registry;
    }

    // ---- small readers

    private static Instant startOf(TestFile.Case c) {
        JsonNode clock = c.get("clock");
        if (clock != null && clock.has("start"))
            try {
                return Instant.parse(clock.get("start").asText());
            } catch (RuntimeException e) {
                // the checker reports a start that is not a time
            }
        return null;
    }

    static Map<String, JsonNode> fields(JsonNode map) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (map != null && map.isObject())
            for (Iterator<Map.Entry<String, JsonNode>> it = map.fields(); it.hasNext(); ) {
                var e = it.next();
                out.put(e.getKey(), e.getValue());
            }
        return out;
    }

    private static List<String> strings(JsonNode list) {
        List<String> out = new ArrayList<>();
        if (list != null && list.isArray()) list.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> inputs(JsonNode in) {
        Map<String, String> input = new LinkedHashMap<>();
        if (in != null && in.isObject())
            in.fields().forEachRemaining(e -> input.put(e.getKey(), text(e.getValue())));
        return input;
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
