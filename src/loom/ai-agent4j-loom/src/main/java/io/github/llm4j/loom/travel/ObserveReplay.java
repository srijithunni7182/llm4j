package io.github.llm4j.loom.travel;

import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.DecisionPoint;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.runtime.Generations;
import io.github.llm4j.loom.runtime.RunHalted;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.runtime.RunStopped;
import io.github.llm4j.loom.runtime.RunSuspended;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a script again over the journal of an earlier run, with nothing that has an effect: the same
 * interpreter, so the same steps and the same decisions, but every result comes from the journal.
 * Where the journal has nothing for a step the replay stops (the real run stopped there, or the
 * script is no longer the one that ran) and says what it needed. No model, tool, task, person,
 * clock or network is touched, and the journal it was given is not written: it works on a copy.
 *
 * <p>This is the one way anything in {@code weave why}, the stepper's {@code back}, {@code whatif}
 * and the run overlay executes a script without effects, so "no effects, exact decisions" is proved
 * once.
 */
public final class ObserveReplay {

    private ObserveReplay() {}

    /**
     * What to replay.
     *
     * @param script the script the run used
     * @param workflow the workflow that ran
     * @param inputs what the workflow was given
     * @param maskedNames variables whose values are never shown
     */
    public record Options(
            Path script,
            String workflow,
            Map<String, String> inputs,
            Set<String> maskedNames,
            java.util.function.Consumer<LoomScript> edit) {
        public Options {
            inputs = inputs == null ? Map.of() : inputs;
            maskedNames = maskedNames == null ? Set.of() : maskedNames;
        }

        public Options(
                Path script, String workflow, Map<String, String> inputs, Set<String> maskedNames) {
            this(script, workflow, inputs, maskedNames, null);
        }
    }

    /**
     * Real models for the steps that need one, under a spend cap, with every effect simulated.
     *
     * @param models the models to call
     * @param maxTokens the most tokens the replay may spend, or null
     * @param maxCost the most it may cost, or null (needs {@code prices})
     * @param prices the price table for {@code maxCost}, or null
     * @param env how the models find their keys
     */
    public record Live(
            io.github.llm4j.loom.execution.LLMClientFactory models,
            Long maxTokens,
            java.math.BigDecimal maxCost,
            io.github.llm4j.budget.PriceTable prices,
            java.util.function.Function<String, String> env) {}

    /** The marker, after a step id, of the variables a what-if set before that step. */
    public static final String SET = "#set";

    /** A failure to inject: the target's calls fail with {@code category}, {@code times} times. */
    public record Fail(String category, int times) {}

    /**
     * What to change while replaying (all optional): an answer for a step that has none recorded,
     * variables set before a step, failures to inject, answers for people, results for tools, and a
     * step to stop before.
     */
    public record Hooks(
            io.github.llm4j.loom.execution.AskHook ask,
            Map<String, Fail> failures,
            List<String> humans,
            Map<String, String> toolResults,
            String until,
            Live live,
            StepGate gate) {
        public Hooks {
            failures = failures == null ? Map.of() : failures;
            humans = humans == null ? List.of() : humans;
            toolResults = toolResults == null ? Map.of() : toolResults;
        }

        public static Hooks none() {
            return new Hooks(null, null, null, null, null, null, null);
        }
    }

    /**
     * Where the journal and the script stopped agreeing.
     *
     * @param step the step the replay was at (or the first journal entry it never reached)
     * @param kind {@code missing} (the script needs a result the journal lacks, though the journal
     *     goes on past it) or {@code unreached} (the journal holds a result for a step the script
     *     no longer reaches)
     * @param detail what differs, in words
     */
    public record Divergence(String step, String kind, String detail) {}

    /**
     * What the replay found.
     *
     * @param status {@code completed}, {@code suspended}, {@code failed}, {@code halted}, {@code
     *     stopped} or {@code needs} (it wanted a result the journal does not hold)
     * @param reason how it ended, in words (a failure's message, what was waited for or wanted)
     * @param stoppedAt the step it was at when it ended, or ""
     * @param steps every step that started, in order
     * @param decisions every place it chose a way
     * @param timeline the steps and the choices together, in the order they happened
     * @param unvisited journal entries for steps the replay never started
     * @param variables the variables at the end
     * @param divergence set when the journal and the script disagree
     * @param journal the replay's own journal at the end: the one it was given, plus what it
     *     recorded (the original is untouched)
     * @param calls the model calls a live replay made (0 for an observing one)
     * @param tokens the tokens those calls used
     */
    /** A step starting ({@code decision} null), or a choice made inside one. */
    public record Event(String step, DecisionPoint decision) {}

    public record Result(
            String status,
            String reason,
            String stoppedAt,
            List<String> steps,
            List<DecisionPoint> decisions,
            List<Event> timeline,
            List<String> unvisited,
            Map<String, Object> variables,
            Divergence divergence,
            Map<String, RunJournal.Entry> journal,
            long calls,
            long tokens) {}

    public static Result run(Options options, RunJournal original) throws java.io.IOException {
        return run(options, original, Hooks.none());
    }

    public static Result run(Options options, RunJournal original, Hooks hooks)
            throws java.io.IOException {
        LoomScript script = new LoomLoader().load(options.script().toString());
        if (options.edit() != null) options.edit().accept(script);
        boolean live = hooks.live() != null;
        if (!live) {
            for (AgentDef a : script.getAgents())
                a.setModel(ObservedCollaborators.PREFIX + a.getName() + ":" + a.getModel());
            for (IntentClassifierDef k : script.getIntentClassifiers())
                k.setModel(
                        ObservedCollaborators.PREFIX + k.getName() + ":" + k.getModel(),
                        k.getModelLine());
        }
        ToolRegistry tools = new ToolRegistry();
        Map<String, ToolDef> declared = new LinkedHashMap<>();
        for (ToolDef t : script.getTools()) declared.put(t.getName(), t);
        Set<String> toolNames = new LinkedHashSet<>(declared.keySet());
        for (AgentDef a : script.getAgents()) toolNames.addAll(a.getTools());
        ObservedCollaborators.Failing failing = new ObservedCollaborators.Failing(hooks.failures());
        if (!live) {
            for (String name : toolNames)
                tools.register(
                        name,
                        ObservedCollaborators.tool(
                                name, declared.get(name), hooks.toolResults(), failing));
            script.getTools().clear();
        }
        TaskRegistry tasks = new TaskRegistry();
        Set<String> taskNames = new LinkedHashSet<>();
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof RunStmt r) taskNames.add(r.getTaskName());
                    });
        for (String name : taskNames) tasks.register(ObservedCollaborators.task(name, failing));

        // the journal is copied: nothing the replay writes reaches the run
        RunJournal copy = RunJournal.inMemory();
        // the rewinds the script made are re-derived (with their conditions), so the attempts
        // they started are not taken as given; an operator's rewind cannot be re-derived from the
        // script, so a run with one is followed as it stands
        boolean rederive = scriptOnly(original);
        original.all()
                .forEach(
                        (key, entry) -> {
                            if (!(rederive && key.equals(Generations.KEY))) copy.put(key, entry);
                        });
        Set<String> journaled = stepKeys(script, original);

        List<String> steps = new ArrayList<>();
        List<DecisionPoint> decisions = new ArrayList<>();
        List<Event> timeline = new ArrayList<>();
        HarnessExecutor executor =
                new HarnessExecutor(
                        script,
                        tools,
                        live ? hooks.live().models() : ObservedCollaborators.models(failing));
        if (!live) executor.setClassifierFactory(ObservedCollaborators.classifiers(failing));
        long[] spent = {0, 0};
        if (live) {
            executor.setSimulate(true);
            executor.setBudgetOverrides(hooks.live().maxTokens(), null, hooks.live().maxCost());
            if (hooks.live().prices() != null) executor.setPriceTable(hooks.live().prices());
            executor.addTraceListener(
                    e -> {
                        if (!io.github.llm4j.loom.execution.TraceEvent.MODEL_CALL.equals(e.type()))
                            return;
                        synchronized (spent) {
                            spent[0]++;
                            spent[1] +=
                                    number(e.data(), "prompt_tokens")
                                            + number(e.data(), "completion_tokens");
                        }
                    });
        }
        executor.setTaskRegistry(tasks);
        executor.setHumanInterface(
                hooks.humans().isEmpty()
                        ? ObservedCollaborators.PEOPLE
                        : ObservedCollaborators.people(new java.util.ArrayDeque<>(hooks.humans())));
        if (hooks.ask() != null) executor.setAskHook(hooks.ask());
        executor.setJournal(copy);
        ObservedCollaborators.FrozenClock clock =
                new ObservedCollaborators.FrozenClock(Instant.parse("2026-01-01T00:00:00Z"));
        executor.setClock(clock);
        executor.setSleeper(clock);
        executor.setBaseDir(options.script().toAbsolutePath().getParent());
        executor.setEnvLookup(live ? hooks.live().env() : k -> null);
        executor.setPromptCatalog(
                io.github.llm4j.loom.prompt.PromptSupport.catalog(script, options.script(), null));
        executor.setStepListener(
                step -> {
                    if (step.equals(hooks.until())) throw new ObserveStop("to go on past " + step);
                    steps.add(step);
                    timeline.add(new Event(step, null));
                    // variables a what-if set before this step are in the journal, so a saved
                    // candidate explains itself
                    copy.get(step + SET)
                            .ifPresent(
                                    e -> {
                                        if (e.value() instanceof Map<?, ?> sets)
                                            sets.forEach(
                                                    (name, value) ->
                                                            executor.bindFromOutside(
                                                                    String.valueOf(name),
                                                                    value,
                                                                    step,
                                                                    "what if: set"));
                                    });
                    if (hooks.gate() != null)
                        hooks.gate()
                                .before(
                                        new StepView(
                                                step,
                                                executor::variableViews,
                                                () -> new LinkedHashMap<>(copy.all()),
                                                spent[0],
                                                spent[1],
                                                path ->
                                                        io.github.llm4j.loom.runtime
                                                                .ConditionEvaluator.resolvePath(
                                                                path, executor.getContext()),
                                                condition ->
                                                        io.github.llm4j.loom.runtime
                                                                .ConditionEvaluator.evaluate(
                                                                condition, executor.getContext()),
                                                (name, value) ->
                                                        executor.bindFromOutside(
                                                                name,
                                                                value,
                                                                step,
                                                                "set while stepping")));
                });
        executor.setDecisionListener(
                d -> {
                    decisions.add(d);
                    timeline.add(new Event(d.step(), d));
                },
                options.maskedNames());
        executor.initialize();

        String status;
        String reason = "";
        try {
            executor.executeWorkflow(options.workflow(), options.inputs());
            status = "completed";
        } catch (ObserveStop stop) {
            String at = steps.isEmpty() ? "" : steps.get(steps.size() - 1);
            var failure = original.get(at + "#failure");
            if (failure.isPresent()) {
                // the real run failed at this step: the journal says why
                status = "failed";
                reason = failureText(failure.get().value());
            } else {
                status = "needs";
                reason = stop.what();
            }
        } catch (io.github.llm4j.budget.BudgetExceeded b) {
            status = "budget";
            reason = String.valueOf(b.getMessage());
        } catch (RunSuspended s) {
            status = "suspended";
            reason = s.prompt() == null ? String.valueOf(s.reason()).toLowerCase() : s.prompt();
        } catch (RunHalted h) {
            status = "halted";
            reason = h.reason();
        } catch (RunStopped s) {
            status = "stopped";
            reason = String.valueOf(s.getMessage());
        } catch (RuntimeException e) {
            status = "failed";
            reason = String.valueOf(e.getMessage());
        }
        String stoppedAt = steps.isEmpty() ? "" : steps.get(steps.size() - 1);
        Map<String, Object> variables = new LinkedHashMap<>(executor.getContext().getAll());
        executor.shutdown();

        Set<String> visited = new LinkedHashSet<>(steps);
        List<String> unvisited = new ArrayList<>();
        for (String key : journaled) if (!visited(key, visited)) unvisited.add(key);
        Divergence divergence = null;
        if (!unvisited.isEmpty()) {
            boolean stoppedForWant = status.equals("needs");
            divergence =
                    stoppedForWant
                            ? new Divergence(
                                    stoppedAt,
                                    "missing",
                                    "the script needs "
                                            + reason
                                            + " at "
                                            + stoppedAt
                                            + ", but the journal goes on past it (it has "
                                            + unvisited.get(0)
                                            + ")")
                            : new Divergence(
                                    unvisited.get(0),
                                    "unreached",
                                    "the journal holds a result for "
                                            + unvisited.get(0)
                                            + ", which the script no longer reaches");
        }
        return new Result(
                status,
                reason,
                stoppedAt,
                steps,
                decisions,
                timeline,
                unvisited,
                variables,
                divergence,
                new LinkedHashMap<>(copy.all()),
                spent[0],
                spent[1]);
    }

    private static final java.util.regex.Pattern BRANCH =
            java.util.regex.Pattern.compile("(.*)/p\\d+");

    /**
     * A step was reached when it started, or when it is a branch of a {@code parallel} block that
     * started (the branches run without announcing themselves one by one).
     */
    private static boolean visited(String key, Set<String> started) {
        if (started.contains(key)) return true;
        var m = BRANCH.matcher(key);
        return m.matches() && started.contains(m.group(1));
    }

    private static long number(Map<String, Object> data, String key) {
        return data != null && data.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static String failureText(Object value) {
        if (value instanceof Map<?, ?> m && m.get("error") != null) {
            Object category = m.get("category");
            return String.valueOf(m.get("error")) + (category == null ? "" : " (" + category + ")");
        }
        return String.valueOf(value);
    }

    /** True when every rewind in the journal was made by the script itself. */
    private static boolean scriptOnly(RunJournal journal) {
        Generations generations = new Generations(journal);
        return generations.any()
                && generations.all().stream().allMatch(b -> "script".equals(b.by()));
    }

    /**
     * The step ids the journal holds a result for: the run's own entries, without what it keeps
     * about itself (usage, boundaries, a suspension, the audit of an effect) and without the
     * markers after a {@code #}.
     */
    static Set<String> stepKeys(LoomScript script, RunJournal journal) {
        Set<String> workflows = new LinkedHashSet<>();
        for (WorkflowDef w : script.getWorkflows()) workflows.add(w.getName());
        Set<String> keys = new LinkedHashSet<>();
        for (String key : journal.all().keySet()) {
            String base = key.contains("#") ? key.substring(0, key.indexOf('#')) : key;
            if (base.contains(":") || !base.contains("/")) continue;
            String head = base.substring(0, base.indexOf('/'));
            if (head.contains(">")) head = head.substring(0, head.indexOf('>'));
            if (!workflows.contains(head)) continue;
            if (key.contains("#") && !key.endsWith("#checkpoint")) continue;
            if (key.endsWith("#checkpoint")) continue;
            if (journal.get(key).filter(e -> "retry".equals(e.kind())).isPresent()) continue;
            keys.add(base);
        }
        return keys;
    }
}
