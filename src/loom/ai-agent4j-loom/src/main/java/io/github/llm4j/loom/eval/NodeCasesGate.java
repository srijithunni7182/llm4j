package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.ast.CheckStmt;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.ScriptValidator.Problem;
import io.github.llm4j.loom.execution.ScriptValidator.Severity;
import io.github.llm4j.loom.policy.PolicyCatalog;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The gate of docs/specs/28-evaluation-first (R4): an extractor, or an evaluator with each policy it
 * checks, cannot be used untested. {@code weave check} fails when an extractor has no golden case
 * that names fields, or when an evaluator has no case that breaks a rule of its policy and no clean
 * case for it. The gate looks at the cases' structure, not at whether they are good ones: that is
 * the reviewer's job ({@code weave eval --requirements}) and {@code weave eval}'s.
 *
 * <p>There is no switch that skips it. It runs wherever the project's dataset is, which is where
 * {@code weave check} runs; a host that loads a script with {@code LoomLoader} never reads the
 * dataset, so it is never refused here.
 */
public final class NodeCasesGate {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String VIOLATED = "violated";
    private static final String CLEAN = "clean";

    private NodeCasesGate() {}

    /** Every problem with the golden cases of the extractors and evaluators the script uses. */
    public static List<Problem> problems(LoomScript script, Path scriptFile) {
        Path dir = DatasetFolder.locate(scriptFile, null);
        List<Problem> out = new ArrayList<>();
        Set<String> usedExtractors = new LinkedHashSet<>();
        Map<String, Set<String>> policiesOf = new LinkedHashMap<>();
        collectUse(script, usedExtractors, policiesOf);
        PolicyCatalog catalog = PolicyCatalog.beside(scriptFile.toAbsolutePath().getParent());
        for (ExtractorDef extractor : script.getExtractors())
            if (usedExtractors.contains(extractor.getName()))
                extractorProblems(extractor, dir, out);
        for (EvaluatorDef evaluator : script.getEvaluators()) {
            Set<String> policies = policiesOf.get(evaluator.getName());
            if (policies != null)
                evaluatorProblems(script, evaluator, policies, catalog, dir, out);
        }
        return out;
    }

    private static void collectUse(
            LoomScript script, Set<String> extractors, Map<String, Set<String>> policiesOf) {
        for (PolicyDef p : script.getPolicies())
            if (p.getCheckedBy() != null)
                policiesOf
                        .computeIfAbsent(p.getCheckedBy(), k -> new LinkedHashSet<>())
                        .add(p.getName());
        for (WorkflowDef w : script.getWorkflows())
            StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof ExtractStmt x) extractors.add(x.getExtractor());
                        if (s instanceof CheckStmt c && c.getEvaluator() != null)
                            policiesOf
                                    .computeIfAbsent(c.getEvaluator(), k -> new LinkedHashSet<>())
                                    .addAll(c.getPolicies());
                    });
    }

    // ── extractors ───────────────────────────────────────────────────────────────────────────

    private static void extractorProblems(ExtractorDef def, Path dir, List<Problem> out) {
        String who = "extractor " + def.getName();
        Loaded loaded = load(dir, def.getName());
        for (String problem : loaded.problems) out.add(error(def.getLine(), who, problem));
        if (loaded.unreadable) return;
        Set<String> seen = new HashSet<>();
        Set<String> fields =
                def.getFields() == null
                        ? Set.of()
                        : new TreeSet<>(def.getFields().getFields().keySet());
        for (JsonNode c : loaded.cases) {
            String id = text(c, "id");
            String label = id == null ? "a case" : "case " + id;
            if (id == null)
                out.add(error(def.getLine(), who, loaded.file + ": a case has no id"));
            else if (!seen.add(id))
                out.add(error(def.getLine(), who, loaded.file + ": the id " + id + " is used twice"));
            if (text(c, "input") == null)
                out.add(error(def.getLine(), who, loaded.file + ": " + label + " has no input text"));
            JsonNode expectedFields = c.get("expected_fields");
            JsonNode expectedMissing = c.get("expected_missing");
            boolean names = false;
            if (expectedFields != null) {
                if (!expectedFields.isObject()) {
                    out.add(error(def.getLine(), who, loaded.file + ": " + label + ": expected_fields must be a map of field to value"));
                } else {
                    names |= expectedFields.size() > 0;
                    expectedFields.fieldNames().forEachRemaining(f -> {
                        if (!fields.contains(f))
                            out.add(error(def.getLine(), who, unknownField(loaded.file, label, f, fields)));
                    });
                }
            }
            if (expectedMissing != null) {
                if (!expectedMissing.isArray()) {
                    out.add(error(def.getLine(), who, loaded.file + ": " + label + ": expected_missing must be a list of field names"));
                } else {
                    names |= expectedMissing.size() > 0;
                    for (JsonNode f : expectedMissing) {
                        String name = f.asText();
                        if (!fields.contains(name))
                            out.add(error(def.getLine(), who, unknownField(loaded.file, label, name, fields)));
                        else if (!def.isOptional(name))
                            out.add(error(def.getLine(), who, loaded.file + ": " + label + ": " + name + " is a required field, so it can never be missing (a required field the text does not state fails the step); list it under optional: or drop it from expected_missing"));
                    }
                }
            }
            if (!names)
                out.add(error(def.getLine(), who, loaded.file + ": " + label + " expects nothing: name fields in expected_fields and/or expected_missing"));
        }
        if (loaded.cases.isEmpty() && loaded.problems.isEmpty())
            out.add(
                    error(
                            def.getLine(),
                            who,
                            "Extractor `"
                                    + def.getName()
                                    + "` has no golden cases. Write at least one in `eval/golden/"
                                    + def.getName().toLowerCase(Locale.ROOT)
                                    + ".yaml` (`weave eval <script> --init` starts the file)."));
    }

    private static String unknownField(String file, String label, String field, Set<String> known) {
        List<String> near = PromptCatalog.nearest(field, List.copyOf(known));
        return file
                + ": "
                + label
                + ": the extractor has no field "
                + field
                + (near.isEmpty() ? " (it has " + String.join(", ", known) + ")" : "; did you mean " + String.join(", ", near) + "?");
    }

    // ── evaluators ───────────────────────────────────────────────────────────────────────────

    private static void evaluatorProblems(
            LoomScript script,
            EvaluatorDef def,
            Set<String> policyNames,
            PolicyCatalog catalog,
            Path dir,
            List<Problem> out) {
        String who = "evaluator " + def.getName();
        Loaded loaded = load(dir, def.getName());
        for (String problem : loaded.problems) out.add(error(def.getLine(), who, problem));
        if (loaded.unreadable) return;
        Map<String, Set<String>> rulesOf = new LinkedHashMap<>();
        for (String name : policyNames) {
            for (PolicyDef p : script.getPolicies()) {
                if (!p.getName().equals(name)) continue;
                catalog.resolve(p)
                        .ifPresent(
                                r ->
                                        rulesOf.put(
                                                name,
                                                new LinkedHashSet<>(
                                                        r.rules().stream().map(x -> x.id()).toList())));
            }
        }
        Map<String, Set<String>> broken = new LinkedHashMap<>();
        Map<String, Set<String>> cleaned = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode c : loaded.cases) {
            String id = text(c, "id");
            String label = id == null ? "a case" : "case " + id;
            if (id == null) out.add(error(def.getLine(), who, loaded.file + ": a case has no id"));
            else if (!seen.add(id))
                out.add(error(def.getLine(), who, loaded.file + ": the id " + id + " is used twice"));
            if (text(c, "text") == null)
                out.add(error(def.getLine(), who, loaded.file + ": " + label + " has no text to judge"));
            String policy = text(c, "policy");
            if (policy == null || !policyNames.contains(policy)) {
                out.add(error(def.getLine(), who, loaded.file + ": " + label + " names the policy " + policy + ", which " + def.getName() + " does not check (it checks " + String.join(", ", policyNames) + ")"));
                continue;
            }
            JsonNode expected = c.get("expected");
            if (expected == null || !expected.isObject() || expected.size() == 0) {
                out.add(error(def.getLine(), who, loaded.file + ": " + label + " expects nothing: write expected: { <rule>: violated | clean }"));
                continue;
            }
            Set<String> rules = rulesOf.get(policy);
            for (var it = expected.fields(); it.hasNext(); ) {
                var e = it.next();
                String verdict = e.getValue().asText();
                if (!VIOLATED.equals(verdict) && !CLEAN.equals(verdict)) {
                    out.add(error(def.getLine(), who, loaded.file + ": " + label + ": " + e.getKey() + " must be violated or clean, not " + verdict));
                    continue;
                }
                if (rules != null && !rules.contains(e.getKey())) {
                    out.add(error(def.getLine(), who, loaded.file + ": " + label + ": " + policy + " has no rule " + e.getKey() + " (it has " + String.join(", ", rules) + ")"));
                    continue;
                }
                (VIOLATED.equals(verdict) ? broken : cleaned)
                        .computeIfAbsent(policy, k -> new LinkedHashSet<>())
                        .add(e.getKey());
            }
        }
        String file = def.getName().toLowerCase(Locale.ROOT);
        for (var entry : rulesOf.entrySet()) {
            for (String rule : entry.getValue()) {
                boolean hasBreak = broken.getOrDefault(entry.getKey(), Set.of()).contains(rule);
                boolean hasClean = cleaned.getOrDefault(entry.getKey(), Set.of()).contains(rule);
                if (hasBreak && hasClean) continue;
                String ref = "`" + entry.getKey() + "." + rule + "`";
                String message;
                if (!hasBreak && !hasClean)
                    message = "Evaluator `" + def.getName() + "` has no case that breaks " + ref + " (and no clean case). A policy rule nobody has tested is not binding: write one of each in `eval/golden/" + file + ".yaml`.";
                else if (!hasBreak)
                    message = "Evaluator `" + def.getName() + "` has no case that breaks " + ref + ". A policy rule nobody has tested is not binding: add a case with `" + rule + ": violated` in `eval/golden/" + file + ".yaml`.";
                else
                    message = "Evaluator `" + def.getName() + "` has no clean case for " + ref + " (a near miss is best). A rule that is only ever tested by breaking it can be satisfied by an evaluator that flags everything: add a case with `" + rule + ": clean` in `eval/golden/" + file + ".yaml`.";
                out.add(error(def.getLine(), who, message));
            }
        }
    }

    /** How many cases the node's file holds; zero when there is no file or it cannot be read. */
    public static int caseCount(Path dir, String node) {
        return load(dir, node).cases.size();
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────

    record Loaded(String file, List<JsonNode> cases, List<String> problems, boolean unreadable) {}

    static Loaded load(Path dir, String node) {
        var found = GoldenDatasets.find(dir, node);
        if (found.isEmpty()) return new Loaded(node + ".yaml", List.of(), List.of(), false);
        Path path = found.get();
        String file = path.getFileName().toString();
        try {
            JsonNode root = YAML.readTree(Files.readString(path));
            if (root == null || root.isNull() || root.isMissingNode())
                return new Loaded(file, List.of(), List.of(), false);
            if (!root.isArray())
                return new Loaded(file, List.of(), List.of(file + ": expected a list of cases, each starting with `- id:`"), true);
            List<JsonNode> cases = new ArrayList<>();
            root.forEach(cases::add);
            return new Loaded(file, cases, List.of(), false);
        } catch (JsonProcessingException e) {
            var at = e.getLocation();
            return new Loaded(file, List.of(), List.of(file + (at == null ? "" : " line " + at.getLineNr()) + ": not valid YAML (" + e.getOriginalMessage() + ")"), true);
        } catch (IOException e) {
            return new Loaded(file, List.of(), List.of(file + ": could not be read (" + e.getMessage() + ")"), true);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static Problem error(int line, String construct, String message) {
        return new Problem(line, construct, message, Severity.ERROR);
    }
}
