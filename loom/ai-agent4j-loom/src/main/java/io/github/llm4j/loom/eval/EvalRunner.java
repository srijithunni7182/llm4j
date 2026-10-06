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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs scenarios against a script and checks what came out. Each scenario gets a fresh executor, so one cannot leak into the next.
 *
 * <p>An agent scenario gives the agent the scenario's input as its task, exactly as the workflow would, and checks the answer
 * ({@code expected_output_contains}, {@code expected_output}), the tools it used ({@code expected_tools}) and each {@code rubric} line.
 * A workflow scenario runs the workflow with the input as its first parameter and checks what its last agent step produced, the tools used, the
 * {@code rubric} lines about that, and each {@code expect} line against an account of the run (the steps in order). A judged line with no
 * judge is {@link Status#UNJUDGED}, never a pass.
 */
public final class EvalRunner {

    /**
     * Makes a fresh, initialised executor for one scenario. {@code beforeInitialize} must be run on it before it is initialised: agents forward
     * their own events (tool calls, thoughts) to a trace listener only if one is there when they are built.
     */
    public interface Executors {
        HarnessExecutor create(java.util.function.Consumer<HarnessExecutor> beforeInitialize) throws Exception;
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
     * @param judges the judge to use for a model name, or null for none (a mock run, or no model to judge with)
     * @param modelOf the model of an agent, so a judge defaults to the agent's own model
     */
    public EvalRunner(Executors executors, Function<String, RubricJudge> judges, Function<String, String> modelOf) {
        this(executors, judges, modelOf, false);
    }

    /**
     * @param mock the model is a mock: its answers say nothing about content, so checks of the answer or the tools are {@link Status#UNJUDGED}
     *     and a scenario fails only when the run itself breaks (which is what a mock run is for)
     */
    public EvalRunner(Executors executors, Function<String, RubricJudge> judges, Function<String, String> modelOf, boolean mock) {
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
            executor = executors.create(e -> { });
            AgentResult result = executor.runAgentTask(agent, s.input());
            String answer = result.getFinalAnswer() == null ? "" : result.getFinalAnswer();
            Set<String> tools = new HashSet<>();
            result.getSteps().forEach(step -> {
                if (step.getAction() != null) tools.add(step.getAction());
            });
            if (result.budgetExhausted()) stoppedByLimit = true;
            answerChecks(s, answer, tools, checks);
            judge("rubric", s.rubricLines(), answer, s, modelOf.apply(agent), checks);
            return finish("agent", agent, file, s, checks, cost(executor), null);
        } catch (BudgetExceeded e) {
            stoppedByLimit = true;
            return finish("agent", agent, file, s, checks, cost(executor), "stopped by a limit: " + e.getMessage());
        } catch (RuntimeException e) {
            return finish("agent", agent, file, s, checks, cost(executor), message(e));
        } catch (Exception e) {
            return finish("agent", agent, file, s, checks, null, message(e));
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    // ── a workflow ───────────────────────────────────────────────────────────────────────────

    public ScenarioResult workflow(String file, String workflow, String firstParameter, EvalScenario s, String judgeModel) {
        List<ScenarioResult.Check> checks = new ArrayList<>();
        HarnessExecutor executor = null;
        List<TraceEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            executor = executors.create(e -> e.addTraceListener(events::add));
            Map<String, String> inputs = firstParameter == null ? Map.of() : Map.of(firstParameter, s.input() == null ? "" : s.input());
            executor.executeWorkflow(workflow, inputs);
            String output = lastResult(executor, events);
            Set<String> tools = new HashSet<>();
            events.stream().filter(e -> TraceEvent.ACTION.equals(e.type()) && e.data().get("tool") != null).forEach(e -> tools.add(String.valueOf(e.data().get("tool"))));
            answerChecks(s, output, tools, checks);
            judge("rubric", s.rubricLines(), output, s, judgeModel, checks);
            judge("expect", s.expectLines(), account(events), s, judgeModel, checks);
            return finish("workflow", workflow, file, s, checks, cost(executor), null);
        } catch (BudgetExceeded e) {
            stoppedByLimit = true;
            return finish("workflow", workflow, file, s, checks, cost(executor), "stopped by a limit: " + e.getMessage());
        } catch (RuntimeException e) {
            return finish("workflow", workflow, file, s, checks, cost(executor), message(e));
        } catch (Exception e) {
            return finish("workflow", workflow, file, s, checks, null, message(e));
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    // ── checks ───────────────────────────────────────────────────────────────────────────────

    private void answerChecks(EvalScenario s, String answer, Set<String> tools, List<ScenarioResult.Check> checks) {
        if (mock) {
            if (s.expectedOutputContains() != null) checks.add(new ScenarioResult.Check("answer contains", s.expectedOutputContains(), Status.UNJUDGED, MOCK_NOTE));
            if (s.expectedOutput() != null) checks.add(new ScenarioResult.Check("answer is", s.expectedOutput(), Status.UNJUDGED, MOCK_NOTE));
            if (s.expectedTools() != null && !s.expectedTools().isEmpty()) checks.add(new ScenarioResult.Check("tools used", String.join(", ", s.expectedTools()), Status.UNJUDGED, MOCK_NOTE));
            return;
        }
        if (s.expectedOutputContains() != null) {
            boolean ok = answer.toLowerCase(Locale.ROOT).contains(s.expectedOutputContains().toLowerCase(Locale.ROOT));
            checks.add(new ScenarioResult.Check("answer contains", s.expectedOutputContains(), ok ? Status.PASS : Status.FAIL, ok ? null : "the answer was: " + cut(answer)));
        }
        if (s.expectedOutput() != null) {
            boolean ok = answer.strip().equals(s.expectedOutput().strip());
            checks.add(new ScenarioResult.Check("answer is", s.expectedOutput(), ok ? Status.PASS : Status.FAIL, ok ? null : "the answer was: " + cut(answer)));
        }
        if (s.expectedTools() != null && !s.expectedTools().isEmpty()) {
            List<String> missing = s.expectedTools().stream().filter(t -> !tools.contains(t)).toList();
            checks.add(new ScenarioResult.Check("tools used", String.join(", ", s.expectedTools()), missing.isEmpty() ? Status.PASS : Status.FAIL,
                    missing.isEmpty() ? null : "not used: " + String.join(", ", missing) + "; used: " + (tools.isEmpty() ? "none" : String.join(", ", new java.util.TreeSet<>(tools)))));
        }
    }

    private void judge(String kind, List<String> lines, String subject, EvalScenario s, String model, List<ScenarioResult.Check> checks) {
        RubricJudge judge = lines.isEmpty() ? null : judges == null ? null : judges.apply(model);
        for (String line : lines) {
            if (judge == null) {
                checks.add(new ScenarioResult.Check(kind, line, Status.UNJUDGED, "no judge ran"));
                continue;
            }
            try {
                JudgeVerdict v = judge.judge(line, subject, s);
                boolean ok = v.score() >= RubricJudge.THRESHOLD;
                checks.add(new ScenarioResult.Check(kind, line, ok ? Status.PASS : Status.FAIL, String.format(Locale.ROOT, "score %.2f: %s", v.score(), v.reason())));
            } catch (RuntimeException e) {
                checks.add(new ScenarioResult.Check(kind, line, Status.UNJUDGED, "the judge failed: " + message(e)));
            }
        }
    }

    private static ScenarioResult finish(String kind, String target, String file, EvalScenario s, List<ScenarioResult.Check> checks, BigDecimal cost, String error) {
        Status status = Status.PASS;
        for (ScenarioResult.Check c : checks) status = status.and(c.status());
        if (error != null) status = Status.FAIL;
        else if (checks.isEmpty()) status = Status.UNJUDGED;
        List<ScenarioResult.Check> shown = new ArrayList<>(checks);
        if (error == null && checks.isEmpty()) {
            shown.add(new ScenarioResult.Check("nothing to check", "", Status.UNJUDGED, "the scenario has no expected_output_contains, expected_output, expected_tools, rubric or expect"));
        }
        return new ScenarioResult(target, kind, file, s.id(), s.name(), status, shown, cost, error);
    }

    // ── what a workflow run says ─────────────────────────────────────────────────────────────

    /** What the last agent step that ended produced: the variable it was stored in, else its text. */
    private static String lastResult(HarnessExecutor executor, List<TraceEvent> events) {
        String variable = null;
        String text = "";
        for (TraceEvent e : events) {
            if (TraceEvent.DELEGATE_START.equals(e.type()) && e.data().get("variable") != null) variable = String.valueOf(e.data().get("variable"));
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
            if (!(TraceEvent.DELEGATE_START.equals(type) || TraceEvent.DELEGATE_END.equals(type) || TraceEvent.ACTION.equals(type)
                    || TraceEvent.APPROVAL.equals(type) || TraceEvent.GUARD.equals(type) || TraceEvent.REWIND.equals(type)
                    || TraceEvent.CHECKPOINT.equals(type) || TraceEvent.DECISION.equals(type) || TraceEvent.TASK_START.equals(type))) {
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
        while (t.getCause() != null && t.getCause() != t && (t.getMessage() == null || t instanceof java.lang.reflect.InvocationTargetException)) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String cut(String s) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= TEXT_CUT ? one : one.substring(0, TEXT_CUT) + "…";
    }
}
