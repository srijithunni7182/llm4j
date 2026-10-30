package io.github.llm4j.loom.eval;

import io.github.llm4j.agent.AgentResult;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.eval.dataset.EvalScenario;
import io.github.llm4j.eval.judge.JudgeVerdict;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.TraceEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs scenarios against a script and checks what came out. Each scenario gets a fresh executor, so
 * one cannot leak into the next.
 *
 * <p>An agent scenario gives the agent the scenario's input as its task, exactly as the workflow
 * would, and checks the answer ({@code expected_output_contains}, {@code expected_output}), the
 * tools it used ({@code expected_tools}) and each {@code rubric} line. A workflow scenario runs the
 * workflow with the input as its first parameter and checks what its last agent step produced, the
 * tools used, the {@code rubric} lines about that, and each {@code expect} line against an account
 * of the run (the steps in order). A judged line with no judge is {@link Status#UNJUDGED}, never a
 * pass.
 */
public final class EvalRunner {

    /**
     * Makes a fresh, initialised executor for one scenario. {@code beforeInitialize} must be run on
     * it before it is initialised: agents forward their own events (tool calls, thoughts) to a
     * trace listener only if one is there when they are built.
     */
    public interface Executors {
        HarnessExecutor create(java.util.function.Consumer<HarnessExecutor> beforeInitialize)
                throws Exception;
    }

    private static final String MOCK_NOTE = "a mock run does not check content";
    private static final int ACCOUNT_STEPS = 60;
    private static final int TEXT_CUT = 160;

    private final Executors executors;
    private final Function<String, RubricJudge> judges;
    private final Function<String, String> modelOf;
    private final boolean mock;
    private boolean stoppedByLimit;

    /**
     * @param judges the judge to use for a model name, or null for none (a mock run, or no model to
     *     judge with)
     * @param modelOf the model of an agent, so a judge defaults to the agent's own model
     */
    public EvalRunner(
            Executors executors,
            Function<String, RubricJudge> judges,
            Function<String, String> modelOf) {
        this(executors, judges, modelOf, false);
    }

    /**
     * @param mock the model is a mock: its answers say nothing about content, so checks of the
     *     answer or the tools are {@link Status#UNJUDGED} and a scenario fails only when the run
     *     itself breaks (which is what a mock run is for)
     */
    public EvalRunner(
            Executors executors,
            Function<String, RubricJudge> judges,
            Function<String, String> modelOf,
            boolean mock) {
        this.mock = mock;
        this.executors = executors;
        this.judges = judges;
        this.modelOf = modelOf;
    }

    /** Whether a limit stopped a run: the caller should stop running scenarios. */
    public boolean stoppedByLimit() {
        return stoppedByLimit;
    }

    // ── an agent ─────────────────────────────────────────────────────────────────────────────

    public ScenarioResult agent(String file, String agent, EvalScenario s) {
        List<ScenarioResult.Check> checks = new ArrayList<>();
        HarnessExecutor executor = null;
        try {
            executor = executors.create(e -> {});
            AgentResult result = executor.runAgentTask(agent, s.input());
            String answer = result.getFinalAnswer() == null ? "" : result.getFinalAnswer();
            boolean limitReached = result.budgetExhausted();
            if (limitReached) stoppedByLimit = true;
            if (answer.isBlank())
                return finish(
                        "agent",
                        agent,
                        file,
                        s,
                        checks,
                        cost(executor),
                        limitReached
                                ? "stopped by a limit: the budget ran out before agent "
                                        + agent
                                        + " answered, so no judge was asked"
                                : "agent "
                                        + agent
                                        + " produced no output (its final answer was empty), so no"
                                        + " judge was asked",
                        facts(executor, s, answer));
            Set<String> tools = new HashSet<>();
            result.getSteps()
                    .forEach(
                            step -> {
                                if (step.getAction() != null) tools.add(step.getAction());
                            });
            answerChecks(s, answer, tools, checks);
            judge("rubric", s.rubricLines(), answer, s, modelOf.apply(agent), checks);
            return finish("agent", agent, file, s, checks, cost(executor), null, facts(executor, s, answer));
        } catch (BudgetExceeded e) {
            stoppedByLimit = true;
            return finish(
                    "agent",
                    agent,
                    file,
                    s,
                    checks,
                    cost(executor),
                    "stopped by a limit: " + e.getMessage(),
                    facts(executor, s, null));
        } catch (RuntimeException e) {
            return finish("agent", agent, file, s, checks, cost(executor), message(e), facts(executor, s, null));
        } catch (Exception e) {
            return finish("agent", agent, file, s, checks, null, message(e), facts(executor, s, null));
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    // ── a workflow ───────────────────────────────────────────────────────────────────────────

    public ScenarioResult workflow(
            String file,
            String workflow,
            String firstParameter,
            EvalScenario s,
            String judgeModel) {
        return workflow(
                file,
                workflow,
                firstParameter == null ? List.of() : List.of(firstParameter),
                s,
                judgeModel);
    }

    /**
     * What is wrong with how a scenario gives a workflow its parameters, or null: a name the
     * workflow does not have, a parameter given twice, or one it has but nothing supplies. {@code
     * input:} is the first parameter; {@code inputs:} gives any by name. A first parameter with
     * nothing supplied is the empty text, as it always was.
     */
    public static String inputProblem(String workflow, List<String> parameters, EvalScenario s) {
        Map<String, String> named = s.namedInputs();
        for (String name : named.keySet()) {
            if (!parameters.contains(name)) {
                return "inputs."
                        + name
                        + ": workflow "
                        + workflow
                        + " has no parameter "
                        + name
                        + (parameters.isEmpty()
                                ? " (it takes none)"
                                : " (it has " + String.join(", ", parameters) + ")");
            }
        }
        if (!parameters.isEmpty() && s.input() != null && named.containsKey(parameters.get(0))) {
            return "both input: and inputs."
                    + parameters.get(0)
                    + " give "
                    + parameters.get(0)
                    + ", the first parameter of "
                    + workflow
                    + "; use one";
        }
        for (int i = 1; i < parameters.size(); i++) {
            if (!named.containsKey(parameters.get(i))) {
                return "workflow "
                        + workflow
                        + " needs "
                        + parameters.get(i)
                        + ": give it under inputs: { "
                        + parameters.get(i)
                        + ": ... }";
            }
        }
        return null;
    }

    private static Map<String, String> workflowInputs(List<String> parameters, EvalScenario s) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!parameters.isEmpty()) {
            String first = parameters.get(0);
            out.put(first, s.input() != null ? s.input() : s.namedInputs().getOrDefault(first, ""));
        }
        s.namedInputs().forEach(out::putIfAbsent);
        return out;
    }

    public ScenarioResult workflow(
            String file,
            String workflow,
            List<String> parameters,
            EvalScenario s,
            String judgeModel) {
        String bad = inputProblem(workflow, parameters, s);
        if (bad != null) return finish("workflow", workflow, file, s, new ArrayList<>(), null, bad);
        List<ScenarioResult.Check> checks = new ArrayList<>();
        HarnessExecutor executor = null;
        List<TraceEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            executor = executors.create(e -> e.addTraceListener(events::add));
            executor.executeWorkflow(workflow, workflowInputs(parameters, s));
            String output = outputOf(executor, events);
            if (output.isBlank())
                return finish(
                        "workflow",
                        workflow,
                        file,
                        s,
                        checks,
                        cost(executor),
                        "the workflow produced no output: it ended without a return and without an"
                                + " answer or a note to read, so no judge was asked. Add a `return`"
                                + " of the text to evaluate",
                        facts(executor, s, output));
            Set<String> tools = new HashSet<>();
            events.stream()
                    .filter(e -> TraceEvent.ACTION.equals(e.type()) && e.data().get("tool") != null)
                    .forEach(e -> tools.add(String.valueOf(e.data().get("tool"))));
            answerChecks(s, output, tools, checks);
            judge("rubric", s.rubricLines(), output, s, judgeModel, checks);
            judge("expect", s.expectLines(), account(events), s, judgeModel, checks);
            return finish("workflow", workflow, file, s, checks, cost(executor), null, facts(executor, s, output));
        } catch (BudgetExceeded e) {
            stoppedByLimit = true;
            return finish(
                    "workflow",
                    workflow,
                    file,
                    s,
                    checks,
                    cost(executor),
                    "stopped by a limit: " + e.getMessage(),
                    facts(executor, s, null));
        } catch (RuntimeException e) {
            return finish("workflow", workflow, file, s, checks, cost(executor), message(e), facts(executor, s, null));
        } catch (Exception e) {
            return finish("workflow", workflow, file, s, checks, null, message(e), facts(executor, s, null));
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    // ── checks ───────────────────────────────────────────────────────────────────────────────

    /**
     * The checks of a case's answer set: the verdict (exact, acceptable, rejected, or matching
     * nothing, which is unjudged and a person's to read), the sources the answer must cite, and the
     * actions that must never happen (in the answer or among the tools used).
     */
    private static void answerSetChecks(
            EvalScenario s,
            String answer,
            String echo,
            Set<String> tools,
            List<ScenarioResult.Check> checks) {
        if (AnswerVerdict.applies(s)) {
            AnswerVerdict v = AnswerVerdict.of(s, answer);
            switch (v.kind()) {
                case EXACT, ACCEPTABLE ->
                        checks.add(
                                new ScenarioResult.Check(
                                        "answer verdict",
                                        "in the accepted set",
                                        Status.PASS,
                                        "verdict: " + v.kind().word() + " (" + v.matched() + ")"));
                case REJECTED ->
                        checks.add(
                                new ScenarioResult.Check(
                                        "answer verdict",
                                        "in the accepted set",
                                        Status.FAIL,
                                        "verdict: rejected (" + v.matched() + "); the answer was: " + echo));
                case UNMATCHED ->
                        checks.add(
                                new ScenarioResult.Check(
                                        "answer verdict",
                                        "in the accepted set",
                                        Status.UNJUDGED,
                                        "verdict: unmatched: it matches nothing the case lists. A person should read it; if it is right, add it to accepted_equivalents. The answer was: "
                                                + echo));
            }
        }
        String normal = AnswerVerdict.normal(answer);
        for (String cite : s.citations()) {
            boolean ok = !AnswerVerdict.normal(cite).isEmpty() && normal.contains(AnswerVerdict.normal(cite));
            checks.add(
                    new ScenarioResult.Check(
                            "answer cites", cite, ok ? Status.PASS : Status.FAIL, ok ? null : "the answer does not cite it"));
        }
        for (String action : s.forbiddenActions()) {
            String a = AnswerVerdict.normal(action);
            boolean taken =
                    !a.isEmpty()
                            && (normal.contains(a)
                                    || tools.stream().anyMatch(t -> AnswerVerdict.normal(t).equals(a)));
            checks.add(
                    new ScenarioResult.Check(
                            "answer never takes",
                            action,
                            taken ? Status.FAIL : Status.PASS,
                            taken ? "the answer or the tools used take it" : null));
        }
    }

    private void answerChecks(
            EvalScenario s, String answer, Set<String> tools, List<ScenarioResult.Check> checks) {
        if (mock) {
            if (s.expectedOutputContains() != null)
                checks.add(
                        new ScenarioResult.Check(
                                "answer contains",
                                s.expectedOutputContains(),
                                Status.UNJUDGED,
                                MOCK_NOTE));
            if (s.expectedOutput() != null)
                checks.add(
                        new ScenarioResult.Check(
                                "answer is", s.expectedOutput(), Status.UNJUDGED, MOCK_NOTE));
            for (String never : s.mustNotContain())
                checks.add(
                        new ScenarioResult.Check(
                                "answer never contains", never, Status.UNJUDGED, MOCK_NOTE));
            if (s.expectedMinWords() != null)
                checks.add(
                        new ScenarioResult.Check(
                                "answer has at least",
                                s.expectedMinWords() + " words",
                                Status.UNJUDGED,
                                MOCK_NOTE));
            if (s.expectedMaxWords() != null)
                checks.add(
                        new ScenarioResult.Check(
                                "answer has at most",
                                s.expectedMaxWords() + " words",
                                Status.UNJUDGED,
                                MOCK_NOTE));
            if (s.expectedTools() != null && !s.expectedTools().isEmpty())
                checks.add(
                        new ScenarioResult.Check(
                                "tools used",
                                String.join(", ", s.expectedTools()),
                                Status.UNJUDGED,
                                MOCK_NOTE));
            if (AnswerVerdict.applies(s))
                checks.add(new ScenarioResult.Check("answer verdict", "in the accepted set", Status.UNJUDGED, MOCK_NOTE));
            for (String cite : s.citations())
                checks.add(new ScenarioResult.Check("answer cites", cite, Status.UNJUDGED, MOCK_NOTE));
            for (String action : s.forbiddenActions())
                checks.add(new ScenarioResult.Check("answer never takes", action, Status.UNJUDGED, MOCK_NOTE));
            return;
        }
        // a failure that quotes the answer must not quote what the case says may never appear
        String lowered = answer.toLowerCase(Locale.ROOT);
        boolean leaks = s.mustNotContain().stream().anyMatch(n -> lowered.contains(n.toLowerCase(Locale.ROOT)));
        String echo = leaks ? WITHHELD : cut(answer);
        if (s.expectedOutputContains() != null) {
            boolean ok =
                    answer.toLowerCase(Locale.ROOT)
                            .contains(s.expectedOutputContains().toLowerCase(Locale.ROOT));
            checks.add(
                    new ScenarioResult.Check(
                            "answer contains",
                            s.expectedOutputContains(),
                            ok ? Status.PASS : Status.FAIL,
                            ok ? null : "the answer was: " + echo));
        }
        for (String never : s.mustNotContain()) {
            boolean ok = !answer.toLowerCase(Locale.ROOT).contains(never.toLowerCase(Locale.ROOT));
            // the failure does not repeat the answer: what it must never contain is what it would
            // be repeating (a card number, a name)
            checks.add(
                    new ScenarioResult.Check(
                            "answer never contains",
                            never,
                            ok ? Status.PASS : Status.FAIL,
                            ok ? null : "the answer contains it"));
        }
        int words = answer.isBlank() ? 0 : answer.strip().split("\\s+").length;
        if (s.expectedMinWords() != null) {
            boolean ok = words >= s.expectedMinWords();
            checks.add(
                    new ScenarioResult.Check(
                            "answer has at least",
                            s.expectedMinWords() + " words",
                            ok ? Status.PASS : Status.FAIL,
                            ok ? null : "the answer has " + words + " words"));
        }
        if (s.expectedMaxWords() != null) {
            boolean ok = words <= s.expectedMaxWords();
            checks.add(
                    new ScenarioResult.Check(
                            "answer has at most",
                            s.expectedMaxWords() + " words",
                            ok ? Status.PASS : Status.FAIL,
                            ok ? null : "the answer has " + words + " words"));
        }
        if (s.expectedOutput() != null) {
            boolean ok = answer.strip().equals(s.expectedOutput().strip());
            checks.add(
                    new ScenarioResult.Check(
                            "answer is",
                            s.expectedOutput(),
                            ok ? Status.PASS : Status.FAIL,
                            ok ? null : "the answer was: " + echo));
        }
        answerSetChecks(s, answer, echo, tools, checks);
        if (s.expectedTools() != null && !s.expectedTools().isEmpty()) {
            List<String> missing =
                    s.expectedTools().stream().filter(t -> !tools.contains(t)).toList();
            checks.add(
                    new ScenarioResult.Check(
                            "tools used",
                            String.join(", ", s.expectedTools()),
                            missing.isEmpty() ? Status.PASS : Status.FAIL,
                            missing.isEmpty()
                                    ? null
                                    : "not used: "
                                            + String.join(", ", missing)
                                            + "; used: "
                                            + (tools.isEmpty()
                                                    ? "none"
                                                    : String.join(
                                                            ", ",
                                                            new java.util.TreeSet<>(tools)))));
        }
    }

    private void judge(
            String kind,
            List<String> lines,
            String subject,
            EvalScenario s,
            String model,
            List<ScenarioResult.Check> checks) {
        RubricJudge judge = lines.isEmpty() ? null : judges == null ? null : judges.apply(model);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            // only rubric lines can name a dimension of their own
            String dimension = kind.equals("rubric") ? s.rubricDimension(i) : null;
            if (judge == null) {
                checks.add(
                        new ScenarioResult.Check(
                                kind, line, Status.UNJUDGED, "no judge ran", dimension));
                continue;
            }
            try {
                JudgeVerdict v = judge.judge(line, subject, s);
                boolean ok = v.score() >= RubricJudge.THRESHOLD;
                checks.add(
                        new ScenarioResult.Check(
                                kind,
                                line,
                                ok ? Status.PASS : Status.FAIL,
                                String.format(
                                        Locale.ROOT, "score %.2f: %s", v.score(), v.reason()),
                                dimension));
            } catch (RubricJudge.UnstableJudgement e) {
                checks.add(
                        new ScenarioResult.Check(
                                kind, line, Status.UNJUDGED, e.getMessage(), dimension));
            } catch (RuntimeException e) {
                checks.add(
                        new ScenarioResult.Check(
                                kind,
                                line,
                                Status.UNJUDGED,
                                "the judge failed: " + message(e),
                                dimension));
            }
        }
    }

    /**
     * What stands in for an output that contains what its case says must never appear: the report
     * and the bundle would otherwise repeat the very thing (a card number, a secret) the case
     * guards against.
     */
    public static final String WITHHELD =
            "(withheld: the output contains text this case says must never appear)";

    private static boolean forbidden(List<ScenarioResult.Check> checks) {
        return checks.stream()
                .anyMatch(c -> c.kind().equals("answer never contains") && c.status() == Status.FAIL);
    }

    /** What a run kept beyond its verdicts: what was judged and what each agent did. */
    private record RunFacts(String output, List<AgentFacts> agents) {
        static final RunFacts NONE = new RunFacts(null, List.of());
    }

    /** The output that was judged and, from the trace, what each agent that took part used. */
    private static RunFacts facts(HarnessExecutor executor, EvalScenario s, String output) {
        if (executor == null) return new RunFacts(output, List.of());
        Map<String, io.github.llm4j.loom.execution.AgentUsage> usage = new LinkedHashMap<>();
        Map<String, Long> cut = Map.of();
        Map<String, Long> fell = Map.of();
        Map<String, java.math.BigDecimal> costs = new LinkedHashMap<>();
        try {
            usage.putAll(executor.usageByAgent());
            cut = executor.truncatedAnswers();
            fell = executor.fallbacksTaken();
            executor.spend().byAgent().forEach((agent, t) -> {
                if (t.cost() != null && !(t.estimated() && t.cost().signum() == 0)) costs.put(agent, t.cost());
            });
        } catch (RuntimeException e) {
            // a run that cannot say what it used still has its output
        }
        java.util.Set<String> names = new java.util.TreeSet<>(usage.keySet());
        names.addAll(cut.keySet());
        names.addAll(fell.keySet());
        List<AgentFacts> out = new ArrayList<>();
        for (String name : names) {
            var u = usage.getOrDefault(name, new io.github.llm4j.loom.execution.AgentUsage(0, 0, 0));
            out.add(
                    new AgentFacts(
                            name,
                            u.calls(),
                            u.promptTokens(),
                            u.completionTokens(),
                            costs.get(name),
                            cut.getOrDefault(name, 0L),
                            fell.getOrDefault(name, 0L)));
        }
        return new RunFacts(output, out);
    }

    private static ScenarioResult finish(
            String kind,
            String target,
            String file,
            EvalScenario s,
            List<ScenarioResult.Check> checks,
            BigDecimal cost,
            String error) {
        return finish(kind, target, file, s, checks, cost, error, RunFacts.NONE);
    }

    private static ScenarioResult finish(
            String kind,
            String target,
            String file,
            EvalScenario s,
            List<ScenarioResult.Check> checks,
            BigDecimal cost,
            String error,
            RunFacts facts) {
        Status status = Status.PASS;
        for (ScenarioResult.Check c : checks) status = status.and(c.status());
        if (error != null) status = Status.FAIL;
        else if (checks.isEmpty()) status = Status.UNJUDGED;
        List<ScenarioResult.Check> shown = new ArrayList<>(checks);
        if (error == null && checks.isEmpty()) {
            shown.add(
                    new ScenarioResult.Check(
                            "nothing to check",
                            "",
                            Status.UNJUDGED,
                            "the scenario has no expected_output_contains, expected_output, expected_tools, rubric, expect, correct_conclusion, accepted_equivalents, rejected, required_citations or unacceptable_actions"));
        }
        return new ScenarioResult(
                target,
                kind,
                file,
                s.id(),
                s.name(),
                status,
                shown,
                cost,
                error,
                s.input(),
                forbidden(shown) ? WITHHELD : facts.output(),
                s.tags(),
                s.dimensions(),
                facts.agents());
    }

    // ── what a workflow run says ─────────────────────────────────────────────────────────────

    /**
     * What the last agent step that ended produced: the variable it was stored in, else its text.
     */
    /**
     * What a scenario is judged on: the workflow's {@code return}; else the answer of the last
     * agent it asked (how evaluations read a workflow before it had a defined output, kept so that
     * they do not change); else its last {@code note}.
     */
    private static String outputOf(HarnessExecutor executor, List<TraceEvent> events) {
        var output = executor.output();
        if (output.source() == io.github.llm4j.loom.execution.WorkflowOutput.Source.RETURN
                && !output.text().isBlank()) return output.text();
        String answered = lastResult(executor, events);
        if (!answered.isBlank()) return answered;
        return output.text();
    }

    private static String lastResult(HarnessExecutor executor, List<TraceEvent> events) {
        String variable = null;
        String text = "";
        for (TraceEvent e : events) {
            if (TraceEvent.DELEGATE_START.equals(e.type()) && e.data().get("variable") != null)
                variable = String.valueOf(e.data().get("variable"));
            if (TraceEvent.DELEGATE_END.equals(e.type())) text = e.text() == null ? "" : e.text();
        }
        Object value = variable == null ? null : executor.getContext().getVariable(variable);
        return value != null ? String.valueOf(value) : text;
    }

    /** The run as a judge reads it: the steps in order, one line each, shortened. */
    static String account(List<TraceEvent> events) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (TraceEvent e : events) {
            String type = e.type();
            if (!(TraceEvent.DELEGATE_START.equals(type)
                    || TraceEvent.DELEGATE_END.equals(type)
                    || TraceEvent.ACTION.equals(type)
                    || TraceEvent.APPROVAL.equals(type)
                    || TraceEvent.GUARD.equals(type)
                    || TraceEvent.REWIND.equals(type)
                    || TraceEvent.CHECKPOINT.equals(type)
                    || TraceEvent.DECISION.equals(type)
                    || TraceEvent.TASK_START.equals(type))) {
                continue;
            }
            if (++n > ACCOUNT_STEPS) {
                out.append("… (more steps not shown)\n");
                break;
            }
            out.append(n).append(". ").append(type.replace('_', ' '));
            if (e.agent() != null) out.append(" [").append(e.agent()).append(']');
            if (e.text() != null && !e.text().isBlank()) out.append(": ").append(cut(e.text()));
            out.append('\n');
        }
        return out.length() == 0 ? "(no steps ran)" : out.toString();
    }

    private static BigDecimal cost(HarnessExecutor executor) {
        try {
            var total = executor == null ? null : executor.spend().total();
            return total == null || total.estimated() && total.cost() == null ? null : total.cost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String message(Exception e) {
        Throwable t = e;
        while (t.getCause() != null
                && t.getCause() != t
                && (t.getMessage() == null
                        || t instanceof java.lang.reflect.InvocationTargetException))
            t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String cut(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= TEXT_CUT ? one : one.substring(0, TEXT_CUT) + "…";
    }
}
