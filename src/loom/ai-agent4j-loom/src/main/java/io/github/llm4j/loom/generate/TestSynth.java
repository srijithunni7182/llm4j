package io.github.llm4j.loom.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.ast.AgentDef;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML =
            YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build();

    /** The most cases kept, and the most scripted runs tried, so a big script stays quick. */
    static final int MAX_CASES = 24;

    static final int MAX_RUNS = 400;

    /** The answers of one scenario: a choice per knob, a failing target, a way people answer. */
    private record Scenario(
            String name,
            Map<String, String> classifier,
            Map<String, String> enums,
            Set<String> failing,
            String human) {}

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
        List<Scenario> order = scenarios(knobs);

        List<ObjectNode> kept = new ArrayList<>();
        Set<String> seenRoutes = new LinkedHashSet<>();
        Set<String> reached = new LinkedHashSet<>();
        int tried = 0;
        for (Scenario s : order) {
            if (tried >= MAX_RUNS || kept.size() >= MAX_CASES) break;
            tried++;
            ObjectNode body = body(loaded, main, knobs, s);
            CaseProbe.Result r;
            try {
                r = CaseProbe.run(script, main.getName(), body);
            } catch (Exception e) {
                continue;
            }
            if (!r.problems().isEmpty() || r.route().isEmpty() || !r.failures().isEmpty()) continue;
            String key = r.status() + "|" + String.join(">", r.route());
            if (seenRoutes.contains(key)) continue;
            ObjectNode expect = body.putObject("expect");
            expect.put("final", finalOf(r.status()));
            ArrayNode route = expect.putArray("route");
            r.route().forEach(route::add);
            if (!holds(script, main, body)) {
                // a route the test file cannot name (a step label it does not know) is left out of
                // the case; the final state and the cases themselves still stand
                expect.remove("route");
                if (!holds(script, main, body)) continue;
            }
            seenRoutes.add(key);
            kept.add(body);
            reached.addAll(r.route());
        }
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
        return new Derived(yaml, kept.size(), tried, reached.size(), steps(script, main));
    }

    private static boolean holds(Path script, WorkflowDef main, ObjectNode body) {
        try {
            CaseProbe.Result r = CaseProbe.run(script, main.getName(), body);
            return r.problems().isEmpty() && r.failures().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static int steps(Path script, WorkflowDef main) {
        try {
            var g = new io.github.llm4j.loom.graph.GraphService().graph(script);
            int n = 0;
            for (var w : g.workflows())
                for (var node : w.nodes())
                    if (!node.kind().equals("start") && !node.kind().equals("end")) n++;
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The workflow the cases run: Main when there is one, else the first. */
    private static WorkflowDef entry(LoomScript s) {
        for (WorkflowDef w : s.getWorkflows()) if (w.getName().equals("Main")) return w;
        return s.getWorkflows().isEmpty() ? null : s.getWorkflows().get(0);
    }

    // ---- what can be varied

    /** The choices a run depends on, each with its possible values (the first is the default). */
    private record Knobs(
            Map<String, Map<String, List<String>>> classifiers,
            Map<String, Map<String, List<String>>> enums,
            Map<String, SchemaDef> schemas,
            List<String> targets,
            List<String> tools) {

        static Knobs of(LoomScript s) {
            Map<String, Map<String, List<String>>> classifiers = new LinkedHashMap<>();
            List<String> targets = new ArrayList<>();
            for (IntentClassifierDef c : s.getIntentClassifiers()) {
                Map<String, List<String>> qs = new LinkedHashMap<>();
                for (IntentClassifierDef.Question q : c.getQuestions()) {
                    List<String> values =
                            q.kind() == IntentClassifierDef.Kind.ASK
                                    ? List.of("yes", "no")
                                    : c.possibleLabels(q);
                    if (!values.isEmpty()) qs.put(q.name(), values);
                }
                classifiers.put(c.getName(), qs);
                targets.add(c.getName());
            }
            Map<String, Map<String, List<String>>> enums = new LinkedHashMap<>();
            Map<String, SchemaDef> schemas = new LinkedHashMap<>();
            // an answer typed on the statement ("expecting { ... }") belongs to the agent it asks
            for (WorkflowDef w : s.getWorkflows())
                StatementWalker.walk(
                        w.getStatements(),
                        st -> {
                            if (st instanceof DelegateStmt d
                                    && d.getExpecting() != null
                                    && d.getTargetAgent() != null)
                                schemas.putIfAbsent(d.getTargetAgent(), d.getExpecting());
                        });
            List<String> tools = new ArrayList<>();
            for (AgentDef a : s.getAgents()) {
                targets.add(a.getName());
                SchemaDef schema =
                        a.getOutputSchema() != null
                                ? a.getOutputSchema()
                                : schemas.get(a.getName());
                if (schema != null) {
                    schemas.put(a.getName(), schema);
                    Map<String, List<String>> fields = new LinkedHashMap<>();
                    collect("", schema, fields);
                    if (!fields.isEmpty()) enums.put(a.getName(), fields);
                }
                for (String t : a.getTools()) if (!tools.contains(t)) tools.add(t);
            }
            for (ToolDef t : s.getTools()) if (!tools.contains(t.getName())) tools.add(t.getName());
            targets.addAll(tools);
            return new Knobs(classifiers, enums, schemas, targets, tools);
        }

        /** The enum and boolean fields of a schema, by their path. */
        private static void collect(String path, SchemaDef d, Map<String, List<String>> out) {
            if (d == null) return;
            switch (d.getType()) {
                case ENUM -> {
                    if (d.getEnumValues() != null && !d.getEnumValues().isEmpty())
                        out.put(path, d.getEnumValues());
                }
                case BOOLEAN -> out.put(path, List.of("true", "false"));
                case OBJECT -> {
                    if (d.getFields() != null)
                        d.getFields()
                                .forEach(
                                        (k, v) ->
                                                collect(
                                                        path.isEmpty() ? k : path + "." + k,
                                                        v,
                                                        out));
                }
                default -> {}
            }
        }
    }

    /**
     * The scenarios, in the order they are tried: the plain one, then one choice changed at a time,
     * then each step failing, then people saying no, then pairs of changed choices for what is
     * still unreached.
     */
    private static List<Scenario> scenarios(Knobs k) {
        List<Scenario> out = new ArrayList<>();
        out.add(new Scenario("the usual path", Map.of(), Map.of(), Set.of(), "yes"));
        List<Scenario> singles = new ArrayList<>();
        k.classifiers()
                .forEach(
                        (c, qs) ->
                                qs.forEach(
                                        (q, values) -> {
                                            for (int i = 1; i < values.size(); i++)
                                                singles.add(
                                                        new Scenario(
                                                                c
                                                                        + " says "
                                                                        + q
                                                                        + " is "
                                                                        + values.get(i),
                                                                Map.of(c + "." + q, values.get(i)),
                                                                Map.of(),
                                                                Set.of(),
                                                                "yes"));
                                        }));
        k.enums()
                .forEach(
                        (a, fields) ->
                                fields.forEach(
                                        (f, values) -> {
                                            for (int i = 1; i < values.size(); i++)
                                                singles.add(
                                                        new Scenario(
                                                                a
                                                                        + " answers "
                                                                        + f
                                                                        + " "
                                                                        + values.get(i),
                                                                Map.of(),
                                                                Map.of(a + "." + f, values.get(i)),
                                                                Set.of(),
                                                                "yes"));
                                        }));
        out.addAll(singles);
        for (String t : k.targets())
            out.add(new Scenario(t + " fails", Map.of(), Map.of(), Set.of(t), "yes"));
        out.add(new Scenario("people say no", Map.of(), Map.of(), Set.of(), "no"));
        for (int i = 0; i < singles.size(); i++)
            for (int j = i + 1; j < singles.size() && out.size() < MAX_RUNS; j++) {
                Scenario a = singles.get(i);
                Scenario b = singles.get(j);
                Map<String, String> cl = new LinkedHashMap<>(a.classifier());
                cl.putAll(b.classifier());
                Map<String, String> en = new LinkedHashMap<>(a.enums());
                en.putAll(b.enums());
                if (cl.size() != a.classifier().size() + b.classifier().size()
                        || en.size() != a.enums().size() + b.enums().size()) continue;
                out.add(new Scenario(a.name() + " and " + b.name(), cl, en, Set.of(), "yes"));
            }
        return out;
    }

    // ---- one scenario as a test case

    private static ObjectNode body(LoomScript s, WorkflowDef main, Knobs k, Scenario sc) {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name(sc));
        ObjectNode input = body.putObject("input");
        for (String p : main.getParameters()) input.put(p, "a sample " + p);
        body.put("repeat", "last");
        ObjectNode models = body.putObject("models");
        for (IntentClassifierDef c : s.getIntentClassifiers()) {
            ObjectNode answer = models.putObject(c.getName());
            for (IntentClassifierDef.Question q : c.getQuestions()) {
                Map<String, List<String>> qs = k.classifiers().get(c.getName());
                List<String> values = qs == null ? null : qs.get(q.name());
                String chosen = sc.classifier().get(c.getName() + "." + q.name());
                if (chosen == null) chosen = values == null ? "yes" : values.get(0);
                answer.put(q.name(), chosen);
            }
        }
        for (AgentDef a : s.getAgents()) {
            SchemaDef schema = k.schemas().get(a.getName());
            if (schema == null) {
                models.put(a.getName(), "a sample answer");
            } else {
                Map<String, String> chosen = new LinkedHashMap<>();
                sc.enums()
                        .forEach(
                                (key, v) -> {
                                    if (key.startsWith(a.getName() + "."))
                                        chosen.put(key.substring(a.getName().length() + 1), v);
                                });
                models.set(a.getName(), value("", schema, chosen));
            }
        }
        ObjectNode tools = body.putObject("tools");
        for (String t : k.tools()) tools.putObject(t).put("returns", "ok");
        if (tools.isEmpty()) body.remove("tools");
        ArrayNode humans = body.putArray("humans");
        for (int i = 0; i < 4; i++) humans.add(sc.human());
        if (!sc.failing().isEmpty()) {
            ObjectNode fail = body.putObject("fail");
            for (String t : sc.failing()) {
                ObjectNode f = fail.putObject(t);
                f.put("always", true);
                f.put("category", "server_error");
            }
        }
        return body;
    }

    private static String name(Scenario sc) {
        String n = sc.name();
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    /** A value for a schema; the enum and boolean fields take what the scenario chose. */
    private static com.fasterxml.jackson.databind.JsonNode value(
            String path, SchemaDef d, Map<String, String> chosen) {
        switch (d.getType()) {
            case OBJECT -> {
                ObjectNode o = JSON.createObjectNode();
                if (d.getFields() != null)
                    d.getFields()
                            .forEach(
                                    (k2, v) ->
                                            o.set(
                                                    k2,
                                                    value(
                                                            path.isEmpty() ? k2 : path + "." + k2,
                                                            v,
                                                            chosen)));
                return o;
            }
            case LIST -> {
                ArrayNode a = JSON.createArrayNode();
                if (d.getElementType() != null) a.add(value(path, d.getElementType(), chosen));
                return a;
            }
            case ENUM -> {
                String v = chosen.get(path);
                if (v == null && d.getEnumValues() != null && !d.getEnumValues().isEmpty())
                    v = d.getEnumValues().get(0);
                return JSON.getNodeFactory().textNode(v == null ? "" : v);
            }
            case BOOLEAN -> {
                String v = chosen.get(path);
                return JSON.getNodeFactory().booleanNode(v == null || v.equals("true"));
            }
            case NUMBER -> {
                return JSON.getNodeFactory().numberNode(1);
            }
            default -> {
                return JSON.getNodeFactory()
                        .textNode("a sample " + (path.isEmpty() ? "text" : path));
            }
        }
    }

    private static String finalOf(String status) {
        return switch (status == null ? "" : status.toLowerCase()) {
            case "completed", "failed", "suspended", "stopped", "budget", "halted" -> status
                    .toLowerCase();
            default -> "completed";
        };
    }
}
