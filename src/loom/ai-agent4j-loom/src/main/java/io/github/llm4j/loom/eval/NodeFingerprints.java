package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.llm4j.loom.ast.AgentDef;
import io.github.llm4j.loom.ast.EvaluatorDef;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import io.github.llm4j.loom.prompt.PromptCatalog;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What determines each node's answers, as short hashes per part (docs/specs/28-evaluation-first,
 * R6): the resolved prompt (with the shared prompt an agent receives), the model, the temperature,
 * the schema or fields, the instructions, the tools and their declared effect, for an evaluator the
 * version and hash of its policies, and the hashes of the node's own golden cases. A run records
 * them; {@code weave eval --changed} compares the current ones with a reference and runs only what
 * differs.
 *
 * <p>It fails closed: a part that cannot be read (a prompt file that is missing or unreadable) gets
 * a value that equals nothing, so the node always counts as changed. Running more is the safe side.
 */
public final class NodeFingerprints {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String FILE = "last-run-fingerprints.json";

    /** The parts of every node: node name to part name to a short hash. */
    public record Fingerprints(Map<String, Map<String, String>> nodes) {}

    private NodeFingerprints() {}

    /** The fingerprints of every agent, extractor and evaluator of the script. */
    public static Fingerprints of(LoomScript script, Path scriptFile) {
        Path dir = DatasetFolder.locate(scriptFile, null);
        PromptCatalog prompts =
                PromptSupport.catalog(script, scriptFile, io.github.llm4j.loom.prompt.PromptSettings.NONE);
        PolicyCatalog policies = PolicyCatalog.beside(scriptFile.toAbsolutePath().getParent());
        Map<String, Map<String, String>> nodes = new TreeMap<>();
        String shared = sharedPrompt(script, prompts);
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        script.getTools().forEach(t -> tools.put(t.getName(), t));
        for (AgentDef a : script.getAgents()) {
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("prompt", hash(resolved(a.getSystemPrompt(), a.getPromptRef(), prompts), shared, a.getPersona()));
            parts.put("model", hash(a.getModel()));
            parts.put("temperature", hash(String.valueOf(a.getTemperature())));
            parts.put("schema", hash(describe(a.getOutputSchema())));
            parts.put("tools", hash(toolsOf(a.getTools(), tools)));
            parts.put("cases", casesHash(dir, a.getName()));
            nodes.put(a.getName(), parts);
        }
        for (ExtractorDef x : script.getExtractors()) {
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("prompt", hash(resolved(x.getInstructions(), x.getPromptRef(), prompts)));
            parts.put("model", hash(x.getModel()));
            parts.put("temperature", hash(String.valueOf(x.getTemperature())));
            parts.put("schema", hash(describe(x.getFields()), String.join(",", x.getOptional())));
            parts.put("cases", casesHash(dir, x.getName()));
            nodes.put(x.getName(), parts);
        }
        for (EvaluatorDef e : script.getEvaluators()) {
            Map<String, String> parts = new LinkedHashMap<>();
            parts.put("model", hash(e.getModel()));
            parts.put("settings", hash(e.getUnsureBelow() + "/" + e.getSamples() + "/" + e.getFallback()));
            parts.put("policies", hash(policiesOf(script, e, policies)));
            parts.put("cases", casesHash(dir, e.getName()));
            nodes.put(e.getName(), parts);
        }
        return new Fingerprints(nodes);
    }

    /**
     * The parts that differ for each node of {@code current}. With no reference every node counts
     * as changed, with the single part {@code no reference}. A node the reference lacks is new.
     */
    public static Map<String, List<String>> changed(
            Fingerprints current, Optional<Fingerprints> reference) {
        Map<String, List<String>> out = new TreeMap<>();
        for (var node : current.nodes().entrySet()) {
            if (reference.isEmpty()) {
                out.put(node.getKey(), List.of("no reference"));
                continue;
            }
            Map<String, String> before = reference.get().nodes().get(node.getKey());
            if (before == null) {
                out.put(node.getKey(), List.of("new node"));
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (var part : node.getValue().entrySet())
                if (!part.getValue().equals(before.get(part.getKey()))) parts.add(part.getKey());
            if (!parts.isEmpty()) out.put(node.getKey(), parts);
        }
        return out;
    }

    /**
     * The workflows whose route reaches any of the named nodes: a {@code delegate} to an agent, an
     * {@code extract}, a {@code check ... using}, or a {@code call} of a workflow that does.
     */
    public static java.util.Set<String> workflowsReaching(
            LoomScript script, java.util.Collection<String> nodes) {
        Map<String, java.util.Set<String>> uses = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> calls = new LinkedHashMap<>();
        for (io.github.llm4j.loom.ast.WorkflowDef w : script.getWorkflows()) {
            java.util.Set<String> used = new java.util.HashSet<>();
            java.util.Set<String> called = new java.util.HashSet<>();
            io.github.llm4j.loom.ast.StatementWalker.walk(
                    w.getStatements(),
                    s -> {
                        if (s instanceof io.github.llm4j.loom.ast.DelegateStmt d) used.add(d.getTargetAgent());
                        if (s instanceof io.github.llm4j.loom.ast.ExtractStmt x) used.add(x.getExtractor());
                        if (s instanceof io.github.llm4j.loom.ast.CheckStmt c && c.getEvaluator() != null)
                            used.add(c.getEvaluator());
                        if (s instanceof io.github.llm4j.loom.ast.CallStmt c) called.add(c.getWorkflowName());
                    });
            uses.put(w.getName(), used);
            calls.put(w.getName(), called);
        }
        java.util.Set<String> reaching = new java.util.TreeSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String w : uses.keySet()) {
                if (reaching.contains(w)) continue;
                boolean hit = uses.get(w).stream().anyMatch(nodes::contains)
                        || calls.get(w).stream().anyMatch(reaching::contains);
                if (hit) {
                    reaching.add(w);
                    grew = true;
                }
            }
        }
        return reaching;
    }

    // ── recording ────────────────────────────────────────────────────────────────────────────

    /** Where the last real run's fingerprints are kept: beside the dataset, in {@code eval/}. */
    public static Path lastRunFile(Path scriptFile) {
        return DatasetFolder.locate(scriptFile, null).getParent().resolve(FILE);
    }

    /** Records these as the last real run's fingerprints. */
    public static void write(Path file, Fingerprints fingerprints) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(fingerprints.nodes()));
    }

    /** Reads a record; empty when it is missing, unreadable, or from a version without fingerprints. */
    public static Optional<Fingerprints> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            Map<String, Map<String, String>> nodes =
                    JSON.readValue(
                            Files.readString(file),
                            new TypeReference<Map<String, Map<String, String>>>() {});
            return nodes == null || nodes.isEmpty() ? Optional.empty() : Optional.of(new Fingerprints(nodes));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // no reference: everything counts as changed
        }
    }

    // ── parts ────────────────────────────────────────────────────────────────────────────────

    private static String sharedPrompt(LoomScript script, PromptCatalog prompts) {
        return resolved(script.getSharedPrompt(), script.getSharedPromptRef(), prompts);
    }

    /** The prompt text, or a value that equals nothing when a referenced prompt cannot be read. */
    private static String resolved(String inline, String ref, PromptCatalog prompts) {
        if (ref == null) return inline == null ? "" : inline;
        if (prompts == null) return unreadable();
        try {
            return prompts.resolve(ref).map(PromptCatalog.Resolved::text).orElseGet(NodeFingerprints::unreadable);
        } catch (RuntimeException e) {
            return unreadable();
        }
    }

    private static String unreadable() {
        return "unreadable:" + UUID.randomUUID();
    }

    private static String toolsOf(List<String> names, Map<String, ToolDef> declared) {
        List<String> parts = new ArrayList<>();
        for (String n : names) {
            ToolDef t = declared.get(n);
            parts.add(n + "=" + (t == null ? "?" : t.getEffect().map(Enum::name).orElse("-")));
        }
        return String.join(",", parts);
    }

    private static String policiesOf(LoomScript script, EvaluatorDef evaluator, PolicyCatalog catalog) {
        StringBuilder b = new StringBuilder();
        for (PolicyDef p : script.getPolicies()) {
            if (!evaluator.getName().equals(p.getCheckedBy())) continue;
            b.append(p.getName())
                    .append(':')
                    .append(catalog.resolve(p).map(r -> r.version() + "/" + r.hash()).orElse(unreadable()))
                    .append(';');
        }
        return b.toString();
    }

    private static String describe(SchemaDef schema) {
        if (schema == null) return "";
        StringBuilder b = new StringBuilder(String.valueOf(schema.getType()));
        if (schema.getEnumValues() != null) b.append(schema.getEnumValues());
        if (schema.getElementType() != null) b.append('<').append(describe(schema.getElementType())).append('>');
        if (schema.getFields() != null)
            schema.getFields().forEach((k, v) -> b.append('{').append(k).append(':').append(describe(v)).append('}'));
        return b.toString();
    }

    private static String casesHash(Path dir, String node) {
        Optional<Path> file = GoldenDatasets.find(dir, node);
        if (file.isEmpty()) return hash("");
        try {
            return hash(Files.readString(file.get()));
        } catch (IOException e) {
            return hash(unreadable());
        }
    }

    private static String hash(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
