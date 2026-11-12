package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.agent.task.TaskRegistry;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.graph.GraphResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks test files against the script before anything runs: a name the script does not have, a
 * classifier answer outside its labels, a step label that does not exist, a key the format does not
 * know. Each problem names the file and case and, where there is a near miss, the nearest valid
 * name, so a renamed agent shows up as a failed check and not as a test that passes for the wrong
 * reason.
 */
public final class TestChecker {

    /** The keys a case may have. */
    static final List<String> CASE_KEYS =
            List.of(
                    "name",
                    "tags",
                    "input",
                    "workflow",
                    "models",
                    "evaluators",
                    "expect",
                    "lenient",
                    "repeat",
                    "fail",
                    "humans",
                    "resume",
                    "tools",
                    "tasks",
                    "real",
                    "clock",
                    "usage",
                    "steer",
                    "stop",
                    "remotes",
                    "channels",
                    "judges");

    /** The keys of {@code expect}. */
    static final List<String> EXPECT_KEYS =
            List.of(
                    "result",
                    "exact",
                    "final",
                    "route",
                    "route_exact",
                    "calls",
                    "attempts",
                    "never",
                    "context",
                    "told",
                    "models_used",
                    "asked",
                    "effects",
                    "ran",
                    "elapsed_virtual",
                    "tokens",
                    "degraded",
                    "fallbacks",
                    "branches",
                    "checks",
                    "actions",
                    "starts",
                    "children",
                    "fired",
                    "steers");

    static final List<String> FINALS =
            List.of("completed", "failed", "suspended", "stopped", "budget", "halted");

    private final LoomScript script;
    private final GraphResult graph;
    private final Set<String> agents = new LinkedHashSet<>();
    private final Set<String> classifiers = new LinkedHashSet<>();
    private final Set<String> extractors = new LinkedHashSet<>();
    private final Set<String> evaluators = new LinkedHashSet<>();
    private final Set<String> remotes = new LinkedHashSet<>();
    private final Set<String> channelNames = new LinkedHashSet<>();
    private final Set<String> tools = new LinkedHashSet<>();
    private final Set<String> tasks = new LinkedHashSet<>();
    private final Set<String> steps;

    public TestChecker(LoomScript script, GraphResult graph) {
        this.script = script;
        this.graph = graph;
        script.getAgents().forEach(a -> agents.add(a.getName()));
        script.getIntentClassifiers().forEach(c -> classifiers.add(c.getName()));
        script.getExtractors().forEach(x -> extractors.add(x.getName()));
        script.getEvaluators().forEach(x -> evaluators.add(x.getName()));
        script.getRemotes().forEach(r -> remotes.add(r.getName()));
        script.getAgentChannels().forEach(ch -> channelNames.add(ch.getName()));
        script.getTools().forEach(t -> tools.add(t.getName()));
        for (AgentDef a : script.getAgents()) tools.addAll(a.getTools());
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof RunStmt r) tasks.add(r.getTaskName());
                        if (s instanceof io.github.llm4j.loom.ast.UseStmt u) tools.add(u.getTool());
                    });
        steps = Labels.known(graph);
    }

    /** Every problem in the files, in order; empty when they are sound. */
    public List<String> check(List<TestFile> files) {
        List<String> problems = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (TestFile f : files) {
            problems.addAll(f.problems());
            if (f.workflow() != null && workflow(f.workflow()) == null)
                problems.add(
                        f.path()
                                + ": workflow '"
                                + f.workflow()
                                + "' is not in the script"
                                + Suggest.hint(f.workflow(), workflowNames()));
            for (TestFile.Case c : f.cases()) {
                if (!names.add(c.name()))
                    problems.add(where(c) + "this name is used by another case");
                check(f, c, problems);
            }
        }
        return problems;
    }

    private void check(TestFile f, TestFile.Case c, List<String> problems) {
        JsonNode body = c.body();
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!CASE_KEYS.contains(key))
                problems.add(
                        where(c)
                                + "unknown key '"
                                + key
                                + "'"
                                + Suggest.hint(key, CASE_KEYS)
                                + planned(key));
        }
        WorkflowDef wf = workflow(c.has("workflow") ? c.get("workflow").asText() : f.workflow());
        if (wf == null) {
            problems.add(where(c) + "which workflow? " + workflowChoice());
        } else {
            JsonNode input = c.get("input");
            Set<String> params = new LinkedHashSet<>(wf.getParameters());
            if (input != null && input.isObject())
                for (Iterator<String> it = input.fieldNames(); it.hasNext(); ) {
                    String k = it.next();
                    if (!params.contains(k))
                        problems.add(
                                where(c)
                                        + "input '"
                                        + k
                                        + "' is not a parameter of "
                                        + wf.getName()
                                        + Suggest.hint(k, params)
                                        + " (it has "
                                        + params
                                        + ")");
                }
            for (String p : params)
                if (input == null || !input.has(p))
                    problems.add(
                            where(c)
                                    + "input '"
                                    + p
                                    + "' is missing ("
                                    + wf.getName()
                                    + " has "
                                    + params
                                    + ")");
        }
        JsonNode scriptedEvaluators = c.get("evaluators");
        if (scriptedEvaluators != null) {
            if (!scriptedEvaluators.isObject())
                problems.add(where(c) + "evaluators: a map of evaluator names to the answer for each rule");
            else
                for (Iterator<String> it = scriptedEvaluators.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!evaluators.contains(name))
                        problems.add(
                                where(c)
                                        + "evaluators: '"
                                        + name
                                        + "' is not an evaluator of the script"
                                        + Suggest.hint(name, evaluators));
                }
        }
        JsonNode models = c.get("models");
        if (models != null) {
            if (!models.isObject())
                problems.add(where(c) + "models: a map of agent or classifier names to answers");
            else
                for (Iterator<String> it = models.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (classifiers.contains(name)) classifier(c, name, models.get(name), problems);
                    else if (!agents.contains(name) && !extractors.contains(name))
                        problems.add(
                                where(c)
                                        + "models: '"
                                        + name
                                        + (extractors.isEmpty()
                                                ? "' is not an agent or classifier of the script"
                                                : "' is not an agent, classifier or extractor of the script")
                                        + Suggest.hint(
                                                name, union(agents, union(classifiers, extractors))));
                }
        }
        scripts(c, "tools", tools, problems);
        scripts(c, "tasks", tasks, problems);
        a2a(c, problems);
        children(c, problems);
        real(c, problems);
        fail(c, problems);
        for (String key : List.of("humans", "resume"))
            if (c.has(key) && !c.get(key).isArray())
                problems.add(where(c) + key + ": a list of answers, in the order they are asked");
        JsonNode clock = c.get("clock");
        if (clock != null) {
            if (!clock.isObject() || clock.size() != 1 || !clock.has("start"))
                problems.add(where(c) + "clock: { start: \"2026-10-01T07:00:00Z\" }");
            else
                try {
                    java.time.Instant.parse(clock.get("start").asText());
                } catch (RuntimeException e) {
                    problems.add(
                            where(c)
                                    + "clock.start: '"
                                    + clock.get("start").asText()
                                    + "' is not a time such as 2026-10-01T07:00:00Z");
                }
        }
        JsonNode usage = c.get("usage");
        if (usage != null && usage.isObject())
            for (Iterator<String> it = usage.fieldNames(); it.hasNext(); ) {
                String a = it.next();
                if (!agents.contains(a))
                    problems.add(
                            where(c)
                                    + "usage: '"
                                    + a
                                    + "' is not an agent"
                                    + Suggest.hint(a, agents));
            }
        interventions(c, problems);
        JsonNode expect = c.get("expect");
        if (expect != null) {
            if (!expect.isObject()) problems.add(where(c) + "expect: a map");
            else expect(c, expect, problems);
        }
    }

    /** {@code steer:} and {@code stop:} name an agent and a call, and a way to stop. */
    private void interventions(TestFile.Case c, List<String> problems) {
        JsonNode steer = c.get("steer");
        if (steer != null) {
            if (!steer.isArray())
                problems.add(
                        where(c) + "steer: a list of { after_call: { Agent: N }, text: \"…\" }");
            else
                for (JsonNode s : steer) {
                    if (!afterCall(c, "steer", s, problems)) continue;
                    if (!s.hasNonNull("text"))
                        problems.add(where(c) + "steer: each needs a text, what the person says");
                }
        }
        JsonNode stop = c.get("stop");
        if (stop != null) {
            if (afterCall(c, "stop", stop, problems)) {
                String mode = stop.path("mode").asText("cancel");
                if (!List.of("cancel", "wrap_up").contains(mode))
                    problems.add(where(c) + "stop.mode: cancel or wrap_up, not '" + mode + "'");
                if (stop.has("outcome"))
                    try {
                        io.github.llm4j.loom.execution.StopOutcome.valueOf(
                                stop.get("outcome").asText().toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        problems.add(
                                where(c)
                                        + "stop.outcome: one of "
                                        + java.util.Arrays.stream(
                                                        io.github.llm4j.loom.execution.StopOutcome
                                                                .values())
                                                .map(
                                                        o ->
                                                                o.name()
                                                                        .toLowerCase(
                                                                                java.util.Locale
                                                                                        .ROOT))
                                                .toList());
                    }
            }
        }
    }

    private boolean afterCall(TestFile.Case c, String key, JsonNode spec, List<String> problems) {
        String at = spec.has("after_chunk") ? "after_chunk" : "after_call";
        JsonNode call = spec.get(at);
        if (call == null || !call.isObject() || call.size() != 1) {
            problems.add(
                    where(c)
                            + key
                            + ": after_call: { Agent: N } (or after_chunk) names one agent and the call or piece of a streamed reply after which it happens");
            return false;
        }
        String agent = call.fieldNames().next();
        if (!agents.contains(agent)) {
            problems.add(
                    where(c)
                            + key
                            + "."
                            + at
                            + ": '"
                            + agent
                            + "' is not an agent"
                            + Suggest.hint(agent, agents));
            return false;
        }
        return true;
    }

    /** {@code tools:} and {@code tasks:} name things the script has. */
    private void scripts(TestFile.Case c, String key, Set<String> known, List<String> problems) {
        JsonNode map = c.get(key);
        if (map == null) return;
        if (!map.isObject()) {
            problems.add(where(c) + key + ": a map of names to results");
            return;
        }
        for (Iterator<String> it = map.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!known.contains(name))
                problems.add(
                        where(c)
                                + key
                                + ": '"
                                + name
                                + "' is not "
                                + (key.equals("tools") ? "a tool" : "a task run by")
                                + " the script"
                                + Suggest.hint(name, known));
        }
    }

    /**
     * {@code real:} names tasks that run their own code. Only a task that changes nothing outside
     * the run may, so a test never sends, pays or writes by accident.
     */
    private void real(TestFile.Case c, List<String> problems) {
        JsonNode real = c.get("real");
        if (real == null) return;
        if (!real.isArray()) {
            problems.add(where(c) + "real: a list of task names, such as real: [RefundPolicy]");
            return;
        }
        TaskRegistry registry = TaskRegistry.discovered();
        for (JsonNode n : real) {
            String name = n.asText();
            String problem = RealTasks.problem(name, tasks, c.get("tasks"), registry);
            if (problem != null)
                problems.add(where(c) + "real: " + problem + Suggest.hint(name, tasks));
        }
    }

    /** {@code fail:} names a target, a schedule and a category the runtime has. */
    private void fail(TestFile.Case c, List<String> problems) {
        JsonNode fail = c.get("fail");
        if (fail == null) return;
        if (!fail.isObject()) {
            problems.add(
                    where(c) + "fail: a map of agent, classifier, tool or task names to failures");
            return;
        }
        Set<String> targets = new LinkedHashSet<>(union(agents, union(classifiers, extractors)));
        targets.addAll(evaluators);
        targets.addAll(tools);
        targets.addAll(tasks);
        for (Iterator<String> it = fail.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            JsonNode spec = fail.get(name);
            if (!targets.contains(name))
                problems.add(
                        where(c)
                                + "fail: '"
                                + name
                                + "' is not an agent, classifier, tool or task of the script"
                                + Suggest.hint(name, targets));
            if (!spec.isObject()
                    || !(spec.has("times") || spec.has("on_calls") || spec.has("always"))) {
                problems.add(
                        where(c)
                                + "fail."
                                + name
                                + ": say when: times: N, on_calls: [2, 5] or always: true");
                continue;
            }
            if (spec.has("category")
                    && !Failures.CATEGORIES.contains(spec.get("category").asText()))
                problems.add(
                        where(c)
                                + "fail."
                                + name
                                + ".category: one of "
                                + Failures.CATEGORIES
                                + Suggest.hint(spec.get("category").asText(), Failures.CATEGORIES));
        }
    }

    private void classifier(TestFile.Case c, String name, JsonNode answer, List<String> problems) {
        IntentClassifierDef def =
                script.getIntentClassifiers().stream()
                        .filter(x -> x.getName().equals(name))
                        .findFirst()
                        .orElse(null);
        if (def == null) return;
        if (answer.isArray() && answer.size() > 0) {
            // one map per call, in order
            for (JsonNode each : answer) classifierAnswer(c, name, def, each, problems);
            return;
        }
        classifierAnswer(c, name, def, answer, problems);
    }

    private void classifierAnswer(
            TestFile.Case c,
            String name,
            IntentClassifierDef def,
            JsonNode answer,
            List<String> problems) {
        if (!answer.isObject()) {
            problems.add(
                    where(c)
                            + "models."
                            + name
                            + ": a map with an answer for each question, or a list of such maps (one per call)");
            return;
        }
        Set<String> questions = new LinkedHashSet<>();
        def.getQuestions().forEach(q -> questions.add(q.name()));
        for (Iterator<String> it = answer.fieldNames(); it.hasNext(); ) {
            String q = it.next();
            if (q.equals("confidence")) continue;
            var question = def.question(q);
            if (question == null) {
                problems.add(
                        where(c)
                                + "models."
                                + name
                                + ": '"
                                + q
                                + "' is not a question of the classifier"
                                + Suggest.hint(q, questions)
                                + " (it asks "
                                + questions
                                + ")");
                continue;
            }
            if (question.kind() == IntentClassifierDef.Kind.CHOOSE) {
                String label = answer.get(q).asText();
                List<String> possible = def.possibleLabels(question);
                if (!possible.contains(label))
                    problems.add(
                            where(c)
                                    + "models."
                                    + name
                                    + "."
                                    + q
                                    + ": '"
                                    + label
                                    + "' is not one of "
                                    + possible
                                    + Suggest.hint(label, possible));
            }
        }
        for (String q : questions)
            if (!answer.has(q))
                problems.add(
                        where(c) + "models." + name + ": no answer for the question '" + q + "'");
    }

    private void expect(TestFile.Case c, JsonNode expect, List<String> problems) {
        for (Iterator<String> it = expect.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!EXPECT_KEYS.contains(key))
                problems.add(
                        where(c)
                                + "expect: unknown key '"
                                + key
                                + "'"
                                + Suggest.hint(key, EXPECT_KEYS)
                                + planned(key));
        }
        if (expect.has("final") && !FINALS.contains(expect.get("final").asText()))
            problems.add(
                    where(c)
                            + "expect.final: one of "
                            + FINALS
                            + ", not '"
                            + expect.get("final").asText()
                            + "'");
        for (String key : List.of("route", "never"))
            if (expect.has(key)) {
                if (!expect.get(key).isArray())
                    problems.add(where(c) + "expect." + key + ": a list of step names");
                else
                    for (JsonNode s : expect.get(key)) {
                        String token = s.asText();
                        if (!stepKnown(token))
                            problems.add(
                                    where(c)
                                            + "expect."
                                            + key
                                            + ": '"
                                            + token
                                            + "' is not a step of the script"
                                            + Suggest.hint(token, steps)
                                            + " (weave test --steps lists them)");
                    }
            }
        for (String key : List.of("calls", "attempts")) {
            if (!expect.has(key)) continue;
            Set<String> names = new LinkedHashSet<>(union(agents, union(classifiers, extractors)));
            names.addAll(evaluators);
            names.addAll(tools);
            names.addAll(tasks);
            if (!expect.get(key).isObject())
                problems.add(where(c) + "expect." + key + ": a map of names to counts");
            else
                for (Iterator<String> it = expect.get(key).fieldNames(); it.hasNext(); ) {
                    String n = it.next();
                    if (!names.contains(n))
                        problems.add(
                                where(c)
                                        + "expect."
                                        + key
                                        + ": '"
                                        + n
                                        + "' is not an agent, classifier, tool or task of the script"
                                        + Suggest.hint(n, names));
                }
        }
        if (expect.has("models_used") && expect.get("models_used").isObject())
            for (Iterator<String> it = expect.get("models_used").fieldNames(); it.hasNext(); ) {
                String n = it.next();
                if (!agents.contains(n))
                    problems.add(
                            where(c)
                                    + "expect.models_used: '"
                                    + n
                                    + "' is not an agent"
                                    + Suggest.hint(n, agents));
            }
        if (expect.has("told") && expect.get("told").isObject())
            for (Iterator<String> it = expect.get("told").fieldNames(); it.hasNext(); ) {
                String n = it.next();
                if (!agents.contains(n))
                    problems.add(
                            where(c)
                                    + "expect.told: '"
                                    + n
                                    + "' is not an agent"
                                    + Suggest.hint(n, agents));
            }
        if (expect.has("elapsed_virtual")
                && expect.get("elapsed_virtual").isTextual()
                && Durations.parse(expect.get("elapsed_virtual").asText()) == null)
            problems.add(
                    where(c)
                            + "expect.elapsed_virtual: '"
                            + expect.get("elapsed_virtual").asText()
                            + "' is not a duration such as 30s, 5m or 2h");
        if (expect.has("ran")) {
            if (!expect.get("ran").isArray())
                problems.add(
                        where(c) + "expect.ran: a list such as [ on_failure of delegate Replier ]");
            else
                for (JsonNode r : expect.get("ran"))
                    if (!r.asText()
                            .matches("on_failure of (delegate|classify|intentclassify|task|remote|ask) \\S+"))
                        problems.add(
                                where(c)
                                        + "expect.ran: '"
                                        + r.asText()
                                        + "' should look like 'on_failure of delegate Replier'");
        }
        if (expect.has("branches")) {
            if (!expect.get("branches").isObject())
                problems.add(
                        where(c) + "expect.branches: a map of step to arm, such as { n2: then }");
            else
                for (Iterator<java.util.Map.Entry<String, JsonNode>> it =
                                expect.get("branches").fields();
                        it.hasNext(); ) {
                    var e = it.next();
                    if (!steps.contains(e.getKey()))
                        problems.add(
                                where(c)
                                        + "expect.branches: '"
                                        + e.getKey()
                                        + "' is not a step of the script"
                                        + Suggest.hint(e.getKey(), steps));
                    if (!List.of("then", "else", "body", "exhausted", "failure", "violation")
                            .contains(e.getValue().asText()))
                        problems.add(
                                where(c)
                                        + "expect.branches."
                                        + e.getKey()
                                        + ": one of then, else, body, exhausted, failure, violation");
                }
        }
        if (expect.has("result")) matchers(c, "expect.result", expect.get("result"), problems);
        if (expect.has("context")) matchers(c, "expect.context", expect.get("context"), problems);
    }

    private void matchers(TestFile.Case c, String path, JsonNode node, List<String> problems) {
        Matchers.badMatcher(node).ifPresent(m -> problems.add(where(c) + path + ": " + m));
        if (node.isObject()
                && !(node.size() == 1 && Matchers.KEYS.contains(node.fieldNames().next())))
            node.fields()
                    .forEachRemaining(
                            e -> matchers(c, path + "." + e.getKey(), e.getValue(), problems));
    }

    // ---- helpers

    private boolean stepKnown(String token) {
        if (steps.contains(token)) return true;
        if (token.startsWith("intentclassify ")) return steps.contains(token);
        return false;
    }

    private WorkflowDef workflow(String name) {
        List<WorkflowDef> all = script.getWorkflows();
        if (name == null) return all.size() == 1 ? all.get(0) : null;
        return all.stream().filter(w -> w.getName().equals(name)).findFirst().orElse(null);
    }

    private List<String> workflowNames() {
        return script.getWorkflows().stream().map(WorkflowDef::getName).toList();
    }

    private String workflowChoice() {
        return "the script has "
                + workflowNames()
                + "; name one with workflow: in the file or the case";
    }

    /** {@code remotes:} and {@code channels:}: names the script declares, steps and answers that make sense. */
    private void a2a(TestFile.Case c, List<String> problems) {
        JsonNode remoteScripts = c.get("remotes");
        if (remoteScripts != null) {
            if (!remoteScripts.isObject())
                problems.add(where(c) + "remotes: a map of remote names to a list of steps");
            else
                for (Iterator<String> it = remoteScripts.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!remotes.contains(name)) {
                        problems.add(where(c) + "remotes: '" + name + "' is not a remote of the script" + Suggest.hint(name, remotes));
                        continue;
                    }
                    JsonNode steps = remoteScripts.get(name);
                    if (!steps.isArray() || steps.isEmpty()) {
                        problems.add(where(c) + "remotes." + name + ": a list of steps, such as [ { state: completed, text: \"…\" } ]");
                        continue;
                    }
                    for (int i = 0; i < steps.size(); i++) remoteStep(c, name, i, steps.get(i), problems);
                }
        }
        JsonNode channelScripts = c.get("channels");
        if (channelScripts != null) {
            if (!channelScripts.isObject())
                problems.add(where(c) + "channels: a map of agent_channel names to a list of answers");
            else
                for (Iterator<String> it = channelScripts.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!channelNames.contains(name))
                        problems.add(where(c) + "channels: '" + name + "' is not an agent_channel of the script" + Suggest.hint(name, channelNames));
                    else if (!channelScripts.get(name).isArray())
                        problems.add(where(c) + "channels." + name + ": a list of answers, in the order they are asked");
                }
        }
    }

    /** {@code judges:} names evaluators of the script; {@code expect.children:} names workflows it starts. */
    private void children(TestFile.Case c, List<String> problems) {
        JsonNode judges = c.get("judges");
        if (judges != null) {
            if (!judges.isObject()) problems.add(where(c) + "judges: a map of evaluator names to a list of verdicts, such as TopicJudge: [pass, fail]");
            else
                for (Iterator<String> it = judges.fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!evaluators.contains(name))
                        problems.add(where(c) + "judges: '" + name + "' is not an evaluator of the script" + Suggest.hint(name, evaluators));
                    else if (!judges.get(name).isArray())
                        problems.add(where(c) + "judges." + name + ": a list of verdicts, pass or fail, one for each time the judge is asked");
                }
        }
        JsonNode expect = c.get("expect");
        if (expect != null && expect.has("children")) {
            Set<String> names = new LinkedHashSet<>(workflowNames());
            if (!expect.get("children").isObject())
                problems.add(where(c) + "expect.children: a map of workflow names to { status, reason }");
            else
                for (Iterator<String> it = expect.get("children").fieldNames(); it.hasNext(); ) {
                    String name = it.next();
                    if (!names.contains(name))
                        problems.add(where(c) + "expect.children: '" + name + "' is not a workflow of the script" + Suggest.hint(name, names));
                }
        }
    }

    private void remoteStep(TestFile.Case c, String name, int i, JsonNode step, List<String> problems) {
        String at = where(c) + "remotes." + name + "[" + (i + 1) + "]: ";
        if (!step.isObject()) {
            problems.add(at + "a step is a map such as { state: working }");
        } else if (step.has("reply")) {
            if (i != 0) problems.add(at + "a plain reply must be the only first step: it ends the call with no task");
        } else {
            String state = step.path("state").asText("");
            if (!ScriptedRemotes.STATES.contains(state))
                problems.add(at + "state '" + state + "' is not one of " + String.join(", ", ScriptedRemotes.STATES) + Suggest.hint(state, ScriptedRemotes.STATES));
            else if (state.equals("input_required") && !step.has("question"))
                problems.add(at + "input_required needs the question the remote asks: question: \"…\"");
            else if (state.equals("auth_required") && !step.has("explanation") && !step.has("question"))
                problems.add(at + "auth_required needs the explanation the remote gives: explanation: \"…\"");
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static String planned(String key) {
        return List.of(
                                "humans", "fail", "tools", "tasks", "clock", "usage", "prices",
                                "steer", "stop", "tags")
                        .contains(key)
                ? ""
                : "";
    }

    private static String where(TestFile.Case c) {
        return c.file() + ": case \"" + c.name() + "\": ";
    }

    /** The graph the checker was built with. */
    GraphResult graph() {
        return graph;
    }
}
