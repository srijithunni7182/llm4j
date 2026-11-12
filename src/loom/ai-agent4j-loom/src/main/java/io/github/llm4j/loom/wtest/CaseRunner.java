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
            Duration elapsedVirtual,
            RunJournal journal,
            Map<String, Long> fallbacks) {}

    /** The most times a case looks again at a remote that has not finished. */
    private static final int MAX_REMOTE_WAITS = 200;

    static Outcome run(Path script, String workflow, TestFile.Case c) {
        long start = System.nanoTime();
        Recorder recorder = new Recorder();
        Map<String, String> modelsUsed = new LinkedHashMap<>();
        VirtualClock clock = new VirtualClock(startOf(c));
        HarnessExecutor executor = null;
        String error = null;
        RunJournal journal = null;
        Map<String, Long> fallbacks = Map.of();
        try {
            Map<String, JsonNode> models = fields(c.get("models"));
            models.putAll(Evaluators.scripted(c.get("evaluators")));
            // a judge of a supervise block answers one word a look, in the order it is asked
            models.putAll(fields(c.get("judges")));
            Map<String, RunJournal> childJournals = new java.util.concurrent.ConcurrentHashMap<>();
            io.github.llm4j.loom.execution.ControlledChildScheduler childScheduler =
                    new io.github.llm4j.loom.execution.ControlledChildScheduler();
            Failures failures = new Failures(c.get("fail"));
            boolean repeatLast = c.has("repeat") && "last".equals(c.get("repeat").asText());
            boolean lenient = c.has("lenient") && c.get("lenient").asBoolean();
            List<String> humans = strings(c.get("humans"));
            ScriptedHumans people = new ScriptedHumans(humans, recorder);
            journal = RunJournal.inMemory();
            LoomScript declared = new LoomLoader().load(script.toString());
            ScriptedRemotes remotes = new ScriptedRemotes(declared, fields(c.get("remotes")), recorder);
            Map<String, JsonNode> channelScripts = fields(c.get("channels"));
            Map<String, ScriptedChannels> channels = new LinkedHashMap<>();
            for (io.github.llm4j.loom.ast.AgentChannelDef ch : declared.getAgentChannels())
                channels.put(ch.getName(), new ScriptedChannels(ch.getName(), ch.get("audience", ""), channelScripts.get(ch.getName()), recorder));
            int remoteWaits = 0;
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
                for (io.github.llm4j.loom.ast.ExtractorDef x : fresh.getExtractors())
                    x.setModel(ScriptedModels.PREFIX + x.getName());
                for (io.github.llm4j.loom.ast.EvaluatorDef x : fresh.getEvaluators())
                    x.setModel(ScriptedModels.PREFIX + x.getName());
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
                                        interventions,
                                        streaming(fresh),
                                        extractorNames(fresh)));
                interventions.attach(executor);
                executor.setClassifierFactory(new ScriptedClassifiers(models, recorder, failures));
                executor.setTaskRegistry(tasks);
                executor.setHumanInterface(people);
                if (!fresh.getRemotes().isEmpty()) executor.setA2ATransport(remotes);
                channels.forEach(executor::setAgentChannel);
                executor.setJournal(journal);
                executor.setRunId("case");
                executor.setChildRuns(id -> childJournals.computeIfAbsent(id, k -> RunJournal.inMemory()));
                executor.setChildScheduler(childScheduler); // children run one at a time, when waited for
                executor.setChildScripts(scriptedChildren(c.get("children")));
                executor.setSuperviseTick(Duration.ZERO); // the virtual clock is the tick
                final HarnessExecutor ticked = executor;
                clock.afterAdvance(ticked::tickChildren);
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
                fallbacks = executor.fallbacksTaken();
                executor.shutdown();
                if (waiting != null
                        && waiting.reason() == io.github.llm4j.loom.runtime.RunSuspended.Reason.REMOTE
                        && remoteWaits++ < MAX_REMOTE_WAITS) {
                    // the run waits for a remote agent: look again, as the host would when told the
                    // task changed; when nothing moved, time passes until the step's deadline
                    if (!remotes.takeProgress() && waiting.resumeAt() != null)
                        clock.sleep(Duration.between(clock.instant(), waiting.resumeAt()));
                    error = null;
                    continue;
                }
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
                    clock.elapsed(),
                    journal,
                    fallbacks);
        } catch (Exception e) {
            return new Outcome(
                    "failed",
                    null,
                    Map.of(),
                    recorder,
                    modelsUsed,
                    message(e),
                    millis(start),
                    clock.elapsed(),
                    journal,
                    fallbacks);
        }
    }

    /**
     * {@code children:} of a case: the workflows whose started children play a script of states
     * instead of running (F5). Each key is a workflow, each value a list of steps {@code { state,
     * after, result, reason }}; the checker has already refused a malformed one.
     */
    /** {@code progress: { stage: reading, done: 1 }} of a scripted step: numbers and booleans keep their type. */
    private static Map<String, Object> progressOf(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNumber() ? (Object) e.getValue().numberValue() : e.getValue().isBoolean() ? (Object) e.getValue().booleanValue() : e.getValue().asText()));
        return out;
    }

    private static io.github.llm4j.loom.execution.ChildScripts scriptedChildren(JsonNode scripts) {
        Map<String, io.github.llm4j.loom.execution.ScriptedChild> byWorkflow = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : fields(scripts).entrySet()) {
            List<io.github.llm4j.loom.execution.ScriptedChild.Step> steps = new ArrayList<>();
            for (JsonNode step : e.getValue()) {
                java.time.Duration after = step.has("after") ? Durations.parse(step.get("after").asText()) : java.time.Duration.ZERO;
                steps.add(
                        new io.github.llm4j.loom.execution.ScriptedChild.Step(
                                io.github.llm4j.loom.execution.ScriptedChild.State.valueOf(step.path("state").asText().toUpperCase(java.util.Locale.ROOT)),
                                after,
                                step.has("result") ? step.get("result").asText() : null,
                                step.has("reason") ? step.get("reason").asText() : null,
                                progressOf(step.get("progress"))));
            }
            byWorkflow.put(e.getKey(), new io.github.llm4j.loom.execution.ScriptedChild(steps));
        }
        return workflow -> java.util.Optional.ofNullable(byWorkflow.get(workflow));
    }

    /** The agents that stream their replies. */
    private static Set<String> extractorNames(LoomScript script) {
        Set<String> names = new LinkedHashSet<>();
        for (io.github.llm4j.loom.ast.ExtractorDef x : script.getExtractors())
            names.add(x.getName());
        for (io.github.llm4j.loom.ast.EvaluatorDef x : script.getEvaluators())
            names.add(x.getName()); // answers with bare JSON, like an extractor
        return names;
    }

    private static Set<String> streaming(LoomScript script) {
        Set<String> names = new LinkedHashSet<>();
        for (AgentDef a : script.getAgents()) if (a.isStreaming()) names.add(a.getName());
        return names;
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
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof io.github.llm4j.loom.ast.UseStmt u) names.add(u.getTool());
                    });
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
        Set<String> real = RealTasks.names(c.get("real"));
        TaskRegistry found = real.isEmpty() ? null : TaskRegistry.discovered();
        for (String name : names)
            registry.register(
                    real.contains(name)
                            ? RealTasks.recorded(found.get(name), recorder)
                            : ScriptedTasks.of(
                                    name, scripts.get(name), failures, repeatLast, recorder));
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
