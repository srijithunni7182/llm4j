package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.AltStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test cases the tool writes itself (phase 12, D13 (a)). The tool knows every classifier label,
 * every enum an agent may answer with, every step that can fail and every question a person may
 * answer, so it runs the script with scripted models and tools, one choice changed at a time, and
 * keeps a case for each route it has not seen. The cases record what the script does, so they pass
 * by construction; what they give the developer is that every branch is reached and that a later
 * change which moves a route shows up as a failing case. What the workflow is <em>meant</em> to do
 * stays with the plan's own cases, written by the model and reported when the script does not
 * follow them.
 *
 * <p>Nothing here calls a model, a tool or the network; a run takes a few milliseconds.
 */
public final class TestSynth {

    private TestSynth() {}

    /** What a condition compares with: conditions are kept without quotes, as a==b or a!=b. */
    private static final Pattern QUOTED = Pattern.compile("(?:==|!=)\\s*\"?([^\"&|()<>=!]*)\"?");

    private static final Pattern NUMBER_AFTER_COMPARE = Pattern.compile("[<>]=?\\s*(\\d{1,6})\\b");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML =
            YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build();

    /** The most cases kept, and the most scripted runs tried, so a big script stays quick. */
    static final int MAX_CASES = 24;

    static final int MAX_RUNS = 500;

    /**
     * One scenario: what is changed from the plain run, as keys ({@code human}, {@code
     * classifier:Name.question}, {@code field:path}, {@code input:param}, {@code fail:Target},
     * {@code first}) and their values.
     */
    private record Scenario(String name, Map<String, String> set) {
        Scenario plus(Change c) {
            Map<String, String> m = new LinkedHashMap<>(set);
            m.put(c.key(), c.value());
            return new Scenario(name.isEmpty() ? c.label() : name + ", " + c.label(), m);
        }

        String signature() {
            return new java.util.TreeMap<>(set).toString();
        }
    }

    /** One thing that can be changed in a run. */
    private record Change(String label, String key, String value) {}

    /** What the tool wrote. */
    public record Derived(String yaml, int cases, int tried, int stepsReached, int steps) {}

    /**
     * Cases for the workflow of {@code script}, as the text of a test file; null when the script
     * cannot be loaded or no case ran.
     */
    public static Derived derive(Path script, String testScriptPath) {
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(script.toString());
        } catch (Exception e) {
            return null;
        }
        WorkflowDef main = entry(loaded);
        if (main == null) return null;

        Knobs knobs = Knobs.of(loaded);
        List<Change> changes = changes(knobs, main);

        io.github.llm4j.loom.graph.GraphResult graph;
        try {
            graph = new io.github.llm4j.loom.graph.GraphService().graph(script);
        } catch (Exception e) {
            return null;
        }
        io.github.llm4j.loom.wtest.StepResolver resolver =
                new io.github.llm4j.loom.wtest.StepResolver(graph);
        Set<String> real = new LinkedHashSet<>();
        for (var w : graph.workflows())
            for (var node : w.nodes())
                if (!node.kind().equals("start") && !node.kind().equals("end"))
                    real.add(w.name() + "/" + node.id());
        int total = real.size();
        Set<String> parallelNodes = new LinkedHashSet<>();
        Set<String> laneLabels = new LinkedHashSet<>();
        for (var w : graph.workflows())
            for (var node : w.nodes()) {
                if (node.kind().equals("parallel")) parallelNodes.add(w.name() + "/" + node.id());
                // a failed branch leaves nothing in the journal, so its label is what gives it away
                if (node.branch() != null && node.branch().startsWith("lane "))
                    laneLabels.add(node.label());
            }

        List<ObjectNode> kept = new ArrayList<>();
        Set<String> seenEnds = new LinkedHashSet<>();
        Set<String> reached = new LinkedHashSet<>();
        int tried = 0;
        // breadth first: the plain run, then every single change of it; a run that reached a step
        // nobody reached is a base for further changes, so a branch behind several conditions
        // (this input, that answer, an unsure classifier) is found one condition at a time
        java.util.ArrayDeque<Scenario> queue = new java.util.ArrayDeque<>();
        Set<String> tried0 = new LinkedHashSet<>();
        Scenario plain = new Scenario("the usual path", Map.of());
        queue.add(plain);
        tried0.add(plain.signature());
        boolean first = true;
        while (!queue.isEmpty()
                && tried < MAX_RUNS
                && kept.size() < MAX_CASES
                && reached.size() < total) {
            Scenario base = queue.poll();
            List<Scenario> round = new ArrayList<>();
            if (first) round.add(base);
            for (Change c : changes) {
                Scenario next = base.plus(c);
                if (next.set().size() == base.set().size()
                        && next.set().get(c.key()).equals(base.set().get(c.key()))) continue;
                if (tried0.add(next.signature())) round.add(next);
            }
            first = false;
            for (Scenario s : round) {
                if (tried >= MAX_RUNS || kept.size() >= MAX_CASES || reached.size() >= total) break;
                tried++;
                ObjectNode body = body(loaded, main, knobs, s);
                CaseProbe.Result r;
                try {
                    r = CaseProbe.run(script, main.getName(), body);
                } catch (Exception e) {
                    continue;
                }
                // a case without expect must complete, so how the run ended is not judged here; it
                // is written into the case and the case is run again below
                if (!r.problems().isEmpty() || r.route().isEmpty()) continue;
                // the steps the run went through, as the graph names them: two halts have the same
                // label and are different steps
                Set<String> nodes = new LinkedHashSet<>();
                for (String id : r.journal().keySet())
                    resolver.resolve(id).filter(real::contains).ifPresent(nodes::add);
                // a failed step and its handler leave no entry in the journal, so a route that has
                // not been seen (by the labels the run reports) counts as new too
                String end = r.status() + "|" + String.join(">", r.route());
                boolean news = !reached.containsAll(nodes) || !seenEnds.contains(end);
                if (!news) continue;
                ObjectNode expect = body.putObject("expect");
                expect.put("final", finalOf(r.status()));
                ArrayNode route = expect.putArray("route");
                r.route().forEach(route::add);
                // the branches of a parallel block finish in whatever order the threads run, so a
                // route through one is not an expectation that holds from one run to the next
                if (nodes.stream().anyMatch(parallelNodes::contains)
                        || r.route().stream().anyMatch(laneLabels::contains)) expect.remove("route");
                if (!holds(script, main, body)) {
                    // a route the test file cannot name (a step label it does not know) is left out
                    // of the case; the final state and the cases themselves still stand
                    expect.remove("route");
                    if (!holds(script, main, body)) continue;
                }
                seenEnds.add(end);
                kept.add(body);
                // the branches of a parallel block run with it, though a run does not name them
                reached.addAll(
                        io.github.llm4j.loom.wtest.Coverage.withLanes(graph, nodes).stream()
                                .filter(real::contains)
                                .toList());
                if (news) queue.add(s);
            }
        }
        if (System.getenv("SYNTH_DEBUG") != null)
            for (ObjectNode k : kept)
                System.err.println(
                        "KEPT "
                                + k.path("name").asText()
                                + " -> "
                                + k.path("expect").path("final").asText()
                                + " "
                                + k.path("expect").path("route"));
        if (kept.isEmpty()) return null;

        ObjectNode file = JSON.createObjectNode();
        file.put("script", testScriptPath);
        file.put("workflow", main.getName());
        ArrayNode cases = file.putArray("cases");
        kept.forEach(cases::add);
        String yaml =
                "# Written by weave generate. Each case runs the script with scripted models and\n"
                        + "# tools and records the route it takes, so every branch is reached and a change\n"
                        + "# that moves a route shows up here. They say what the script DOES, not what it\n"
                        + "# should do: the cases in main.test.yaml say that. Regenerated on every build.\n";
        try {
            yaml += YAML.writeValueAsString(file);
        } catch (Exception e) {
            return null;
        }
        return new Derived(yaml, kept.size(), tried, reached.size(), total);
    }

    /**
     * One answer for an agent asked for several shapes (a draft, then a publish receipt): an object
     * with the fields of all of them, which every one of the questions can read.
     */
    private static SchemaDef merged(SchemaDef a, SchemaDef b) {
        if (a.getType() != SchemaDef.Type.OBJECT || b.getType() != SchemaDef.Type.OBJECT) return a;
        SchemaDef out = new SchemaDef(SchemaDef.Type.OBJECT);
        Map<String, SchemaDef> fields = new LinkedHashMap<>();
        if (a.getFields() != null) fields.putAll(a.getFields());
        if (b.getFields() != null) b.getFields().forEach(fields::putIfAbsent);
        out.setFields(fields);
        return out;
    }

    private static boolean holds(Path script, WorkflowDef main, ObjectNode body) {
        try {
            CaseProbe.Result r = CaseProbe.run(script, main.getName(), body);
            return r.problems().isEmpty() && r.failures().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** The workflow the cases run: Main when there is one, else the first. */
    private static WorkflowDef entry(LoomScript s) {
        for (WorkflowDef w : s.getWorkflows()) if (w.getName().equals("Main")) return w;
        return s.getWorkflows().isEmpty() ? null : s.getWorkflows().get(0);
    }

    // ---- what can be varied

    /**
     * The choices a run depends on. {@code classifiers} and {@code fields} give, for each, the
     * values other than the default one; {@code literals} and {@code numbers} are what the script's
     * own conditions compare with, so they are the values worth trying.
     */
    private record Knobs(
            Map<String, Map<String, List<String>>> classifiers,
            Map<String, List<String>> fields,
            Map<String, SchemaDef> schemas,
            List<String> targets,
            List<String> tools,
            List<String> literals,
            List<Integer> numbers) {

        static Knobs of(LoomScript s) {
            List<String> literals = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            for (WorkflowDef w : s.getWorkflows())
                StatementWalker.walk(
                        w.getStatements(),
                        st -> {
                            if (!(st instanceof AltStmt a) || a.getCondition() == null) return;
                            Matcher m = QUOTED.matcher(a.getCondition());
                            while (m.find()) {
                                String lit = m.group(1).strip();
                                if (lit.isEmpty() || lit.length() > 40) continue;
                                if (lit.matches("\\d{1,6}")) {
                                    Integer n = Integer.valueOf(lit);
                                    if (!numbers.contains(n)) numbers.add(n);
                                } else if (!literals.contains(lit)) literals.add(lit);
                            }
                            Matcher n = NUMBER_AFTER_COMPARE.matcher(a.getCondition());
                            while (n.find()) {
                                Integer v = Integer.valueOf(n.group(1));
                                if (!numbers.contains(v)) numbers.add(v);
                            }
                        });

            Map<String, Map<String, List<String>>> classifiers = new LinkedHashMap<>();
            List<String> targets = new ArrayList<>();
            for (IntentClassifierDef c : s.getIntentClassifiers()) {
                Map<String, List<String>> qs = new LinkedHashMap<>();
                for (IntentClassifierDef.Question q : c.getQuestions()) {
                    List<String> values =
                            q.kind() == IntentClassifierDef.Kind.ASK
                                    ? List.of("yes", "no")
                                    : c.possibleLabels(q);
                    if (values.isEmpty()) continue;
                    // "unsure" is what a classifier that escalates says when it cannot decide
                    List<String> rest = new ArrayList<>(values.subList(1, values.size()));
                    if (q.kind() == IntentClassifierDef.Kind.CHOOSE && !rest.contains("unsure"))
                        rest.add("unsure");
                    if (!rest.isEmpty()) qs.put(q.name(), rest);
                }
                classifiers.put(c.getName(), qs);
                targets.add(c.getName());
            }
            Map<String, SchemaDef> schemas = new LinkedHashMap<>();
            // an answer typed on the statement ("expecting { ... }") belongs to the agent it asks
            for (WorkflowDef w : s.getWorkflows())
                StatementWalker.walk(
                        w.getStatements(),
                        st -> {
                            if (st instanceof DelegateStmt d
                                    && d.getExpecting() != null
                                    && d.getTargetAgent() != null)
                                schemas.merge(
                                        d.getTargetAgent(), d.getExpecting(), TestSynth::merged);
                        });
            Map<String, List<String>> fields = new LinkedHashMap<>();
            List<String> tools = new ArrayList<>();
            for (AgentDef a : s.getAgents()) {
                targets.add(a.getName());
                SchemaDef schema =
                        a.getOutputSchema() != null
                                ? a.getOutputSchema()
                                : schemas.get(a.getName());
                if (schema != null) {
                    schemas.put(a.getName(), schema);
                    collect(a.getName(), schema, literals, numbers, fields);
                }
                for (String t : a.getTools()) if (!tools.contains(t)) tools.add(t);
            }
            for (ToolDef t : s.getTools()) if (!tools.contains(t.getName())) tools.add(t.getName());
            targets.addAll(tools);
            // what a tool returns can be what a later condition compares with
            for (String t : tools)
                if (!literals.isEmpty())
                    fields.put("tool:" + t, literals.stream().limit(4).toList());
            return new Knobs(classifiers, fields, schemas, targets, tools, literals, numbers);
        }

        /** The fields of a schema, by path, each with the values other than the default. */
        private static void collect(
                String path,
                SchemaDef d,
                List<String> literals,
                List<Integer> numbers,
                Map<String, List<String>> out) {
            if (d == null) return;
            switch (d.getType()) {
                case ENUM -> {
                    List<String> v = d.getEnumValues();
                    if (v != null && v.size() > 1) out.put(path, v.subList(1, v.size()));
                }
                case BOOLEAN -> out.put(path, List.of("false"));
                case STRING -> {
                    List<String> v = new ArrayList<>(List.of(""));
                    literals.stream().limit(4).forEach(v::add);
                    out.put(path, v);
                }
                case NUMBER -> {
                    List<String> v = new ArrayList<>(List.of("0"));
                    numbers.stream().limit(3).forEach(n -> v.add(String.valueOf(n + 1)));
                    out.put(path, v);
                }
                case LIST -> {
                    List<String> v = new ArrayList<>(List.of("0"));
                    numbers.stream()
                            .limit(3)
                            .filter(n -> n < 120)
                            .forEach(n -> v.add(String.valueOf(n + 1)));
                    out.put(path, v);
                }
                case OBJECT -> {
                    if (d.getFields() != null)
                        d.getFields()
                                .forEach(
                                        (k, v) ->
                                                collect(path + "." + k, v, literals, numbers, out));
                }
                default -> {}
            }
        }
    }

    /**
     * Every single change a run can have: how people answer (yes, what the script's conditions
     * compare with, no), a classifier label, a field of an agent's answer or a tool's result, an
     * input, a step failing, and "only the first time" for the answers changed so far.
     */
    private static List<Change> changes(Knobs k, WorkflowDef main) {
        List<Change> out = new ArrayList<>();
        List<String> humans = new ArrayList<>(List.of("yes"));
        for (String l : k.literals()) if (humans.size() < 5 && !humans.contains(l)) humans.add(l);
        humans.add("no");
        for (String h : humans) out.add(new Change("people answer " + h, "human", h));
        for (var c : k.classifiers().entrySet())
            for (var q : c.getValue().entrySet())
                for (String v : q.getValue())
                    out.add(
                            new Change(
                                    c.getKey() + " says " + q.getKey() + " is " + v,
                                    "classifier:" + c.getKey() + "." + q.getKey(),
                                    v));
        for (var f : k.fields().entrySet())
            for (String v : f.getValue())
                out.add(
                        new Change(
                                f.getKey() + " is " + (v.isEmpty() ? "empty" : v),
                                "field:" + f.getKey(),
                                v));
        for (String p : main.getParameters()) {
            out.add(new Change(p + " is empty", "input:" + p, ""));
            for (String l : k.literals().stream().limit(4).toList())
                out.add(new Change(p + " is " + l, "input:" + p, l));
            for (String n : List.of("0", "-1"))
                out.add(new Change(p + " is " + n, "input:" + p, n));
            for (int n : k.numbers().stream().limit(3).toList())
                out.add(
                        new Change(
                                p + " is " + (n + 1) + " characters long",
                                "input:" + p,
                                "x".repeat(Math.min(n + 1, 5000))));
        }
        for (String p : main.getParameters()) {
            out.add(
                    new Change(
                            p + " is a list of three", "input:" + p, "json:[\"a\",\"b\",\"c\"]"));
            out.add(new Change(p + " is an empty list", "input:" + p, "json:[]"));
            for (int n : k.numbers().stream().limit(2).toList())
                if (n < 120)
                    out.add(
                            new Change(
                                    p + " is a list of " + (n + 1),
                                    "input:" + p,
                                    "json:" + "[" + "\"x\",".repeat(n) + "\"x\"]"));
        }
        for (String t : k.targets()) out.add(new Change(t + " fails", "fail:" + t, "true"));
        out.add(new Change("the first time only", "first", "true"));
        return out;
    }

    // ---- one scenario as a test case

    private static ObjectNode body(LoomScript s, WorkflowDef main, Knobs k, Scenario sc) {
        Map<String, String> classifier = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        Set<String> failing = new LinkedHashSet<>();
        for (var e : sc.set().entrySet()) {
            String key = e.getKey();
            if (key.startsWith("classifier:")) classifier.put(key.substring(11), e.getValue());
            else if (key.startsWith("field:")) fields.put(key.substring(6), e.getValue());
            else if (key.startsWith("input:")) inputs.put(key.substring(6), e.getValue());
            else if (key.startsWith("fail:")) failing.add(key.substring(5));
        }
        boolean first = sc.set().containsKey("first");
        String human = sc.set().getOrDefault("human", "yes");

        ObjectNode body = JSON.createObjectNode();
        String name = sc.name();
        body.put("name", name.length() > 100 ? name.substring(0, 100) : name);
        ObjectNode input = body.putObject("input");
        for (String p : main.getParameters()) {
            String given = inputs.get(p);
            if (given != null && given.startsWith("json:")) {
                try {
                    input.set(p, JSON.readTree(given.substring(5)));
                } catch (Exception e) {
                    input.put(p, "");
                }
            } else if (given != null) {
                input.put(p, given);
            } else {
                input.put(p, "a sample " + p);
            }
        }
        body.put("repeat", "last");
        ObjectNode models = body.putObject("models");
        for (IntentClassifierDef c : s.getIntentClassifiers()) {
            boolean varied =
                    classifier.keySet().stream().anyMatch(x -> x.startsWith(c.getName() + "."));
            if (varied && first) {
                ArrayNode list = models.putArray(c.getName());
                list.add(classifierAnswer(c, k, classifier));
                list.add(classifierAnswer(c, k, Map.of()));
            } else {
                models.set(c.getName(), classifierAnswer(c, k, classifier));
            }
        }
        for (AgentDef a : s.getAgents()) {
            SchemaDef schema = k.schemas().get(a.getName());
            if (schema == null) {
                models.put(a.getName(), "a sample answer");
                continue;
            }
            Map<String, String> chosen = new LinkedHashMap<>();
            fields.forEach(
                    (key, v) -> {
                        if (key.equals(a.getName()) || key.startsWith(a.getName() + "."))
                            chosen.put(key, v);
                    });
            if (!chosen.isEmpty() && first) {
                ArrayNode list = models.putArray(a.getName());
                list.add(value(a.getName(), schema, chosen));
                list.add(value(a.getName(), schema, Map.of()));
            } else {
                models.set(a.getName(), value(a.getName(), schema, chosen));
            }
        }
        ObjectNode tools = body.putObject("tools");
        for (String t : k.tools()) {
            String returned = fields.get("tool:" + t);
            tools.putObject(t).put("returns", returned == null ? "ok" : returned);
        }
        if (tools.isEmpty()) body.remove("tools");
        ArrayNode humans = body.putArray("humans");
        for (int i = 0; i < 4; i++) humans.add(human);
        if (!failing.isEmpty()) {
            ObjectNode fail = body.putObject("fail");
            for (String t : failing) {
                ObjectNode f = fail.putObject(t);
                f.put("always", true);
                f.put("category", "server_error");
            }
        }
        return body;
    }

    private static ObjectNode classifierAnswer(
            IntentClassifierDef c, Knobs k, Map<String, String> chosen) {
        ObjectNode answer = JSON.createObjectNode();
        for (IntentClassifierDef.Question q : c.getQuestions()) {
            String picked = chosen.get(c.getName() + "." + q.name());
            if (picked == null) {
                List<String> all =
                        q.kind() == IntentClassifierDef.Kind.ASK
                                ? List.of("yes", "no")
                                : c.possibleLabels(q);
                picked = all.isEmpty() ? "yes" : all.get(0);
            }
            answer.put(q.name(), picked);
        }
        return answer;
    }

    /** A value for a schema; a field the scenario changed takes what it chose. */
    private static com.fasterxml.jackson.databind.JsonNode value(
            String path, SchemaDef d, Map<String, String> chosen) {
        String pick = chosen.get(path);
        switch (d.getType()) {
            case OBJECT -> {
                ObjectNode o = JSON.createObjectNode();
                if (d.getFields() != null)
                    d.getFields().forEach((k2, v) -> o.set(k2, value(path + "." + k2, v, chosen)));
                return o;
            }
            case LIST -> {
                ArrayNode a = JSON.createArrayNode();
                int n = 1;
                if (pick != null) {
                    try {
                        n = Math.min(Integer.parseInt(pick), 120);
                    } catch (NumberFormatException ignored) {
                        // the default stays
                    }
                }
                for (int i = 0; i < n && d.getElementType() != null; i++)
                    a.add(value(path + "[]", d.getElementType(), chosen));
                return a;
            }
            case ENUM -> {
                String v = pick;
                if (v == null && d.getEnumValues() != null && !d.getEnumValues().isEmpty())
                    v = d.getEnumValues().get(0);
                return JSON.getNodeFactory().textNode(v == null ? "" : v);
            }
            case BOOLEAN -> {
                return JSON.getNodeFactory().booleanNode(pick == null || pick.equals("true"));
            }
            case NUMBER -> {
                int n = 1;
                if (pick != null) {
                    try {
                        n = Integer.parseInt(pick);
                    } catch (NumberFormatException ignored) {
                        // the default stays
                    }
                }
                return JSON.getNodeFactory().numberNode(n);
            }
            default -> {
                return JSON.getNodeFactory()
                        .textNode(pick != null ? pick : "a sample " + lastPart(path));
            }
        }
    }

    private static String lastPart(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "text" : path.substring(dot + 1);
    }

    private static String finalOf(String status) {
        return switch (status == null ? "" : status.toLowerCase()) {
            case "completed", "failed", "suspended", "stopped", "budget", "halted" -> status
                    .toLowerCase();
            default -> "completed";
        };
    }
}
