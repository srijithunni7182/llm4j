package io.github.llm4j.loom.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.loom.ast.BroadcastStmt;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.ForEachStmt;
import io.github.llm4j.loom.ast.HandoffStmt;
import io.github.llm4j.loom.ast.HumanPromptStmt;
import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ParallelStmt;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.ast.Statement;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.runtime.RunJournal;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a past run into a test case: the models, classifiers, people and failures that were
 * recorded become the scripted collaborators of a phase 11 case, so the case reproduces what
 * production did with no model. Nothing is invented. The case is built, masked and run in memory
 * first; it is written only if it passes, and what was masked never reaches a file.
 */
public final class Capture {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML =
            YAMLMapper.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .enable(YAMLGenerator.Feature.SPLIT_LINES)
                    .build();

    private Capture() {}

    /** What to expect of the captured case. */
    public enum Expect {
        /** Fill the expectations from what happened. */
        OBSERVED,
        /** Only that the run ends as it did. */
        NONE
    }

    /**
     * @param to where the case goes (null: {@code tests/captured/} beside the script)
     * @param maskTerms names and words a pattern cannot find, to be masked
     * @param maskKeep input names or agent names whose values are not masked (the developer takes
     *     the responsibility)
     * @param denyFields input or variable names whose values are never captured
     * @param mask false to leave personal data alone (secrets are always taken out)
     */
    public record Options(
            Path to,
            Expect expect,
            Set<String> maskTerms,
            Set<String> maskKeep,
            Set<String> denyFields,
            boolean mask,
            String note,
            List<String> tags,
            String name) {
        public Options(
                Path to,
                Expect expect,
                Set<String> maskTerms,
                Set<String> maskKeep,
                Set<String> denyFields,
                boolean mask,
                String note,
                List<String> tags) {
            this(to, expect, maskTerms, maskKeep, denyFields, mask, note, tags, null);
        }

        public Options {
            expect = expect == null ? Expect.OBSERVED : expect;
            maskTerms = maskTerms == null ? Set.of() : maskTerms;
            maskKeep = maskKeep == null ? Set.of() : maskKeep;
            denyFields = denyFields == null ? Set.of() : denyFields;
            tags = tags == null ? List.of() : tags;
        }

        public static Options defaults() {
            return new Options(null, Expect.OBSERVED, null, null, null, true, null, null, null);
        }
    }

    /**
     * @param file where the case is or would be
     * @param text the case as it would be written
     * @param written true when the file was written by this call
     * @param unchanged true when the file already held exactly this
     * @param conflict true when a different file is there (hand-edited): nothing was written
     * @param masked what kinds of value were masked (never the values)
     */
    public record Captured(
            Path file,
            String text,
            boolean written,
            boolean unchanged,
            boolean conflict,
            List<String> masked) {}

    public static Captured capture(RunRecord run, Options options, boolean write) throws Exception {
        Path script = run.script();
        if (!Files.isRegularFile(script))
            throw new IllegalArgumentException(
                    run.id() + " ran " + script + ", which is not there any more.");
        String now = ScriptHash.of(script);
        if (run.scriptHash() != null && !run.scriptHash().equals(now))
            throw new IllegalArgumentException(
                    run.id()
                            + " ran a different version of "
                            + script.getFileName()
                            + " than the one in the folder now, so a case taken from it would not be"
                            + " what production did. Check out the version that ran (its hash is "
                            + run.scriptHash().substring(0, Math.min(12, run.scriptHash().length()))
                            + "...) and capture again.");
        LoomScript loaded = new LoomLoader().load(script.toString());
        ObserveReplay.Result replay =
                ObserveReplay.run(
                        new ObserveReplay.Options(script, run.workflow(), run.inputs(), Set.of()),
                        run.asJournal());
        if (replay.divergence() != null)
            throw new IllegalArgumentException(
                    run.id()
                            + " cannot be replayed from its journal: "
                            + replay.divergence().detail());
        if (replay.status().equals("needs"))
            throw new IllegalArgumentException(
                    run.id()
                            + " has no recorded answer for "
                            + replay.reason()
                            + ", so it cannot be reproduced without a model.");
        Collected c = collect(loaded, replay, run);

        // the case as it happened (never written) and as it will be written (masked)
        Masker masker = options.mask() ? new Masker(options.maskTerms()) : new Masker(List.of());
        ObjectNode raw = body(run, c, replay, options, null);
        ObjectNode masked = body(run, c, replay, options, masker);

        String final0 = finalOf(replay.status());
        raw.set("expect", JSON.createObjectNode().put("final", final0));
        CaseProbe.Result happened = CaseProbe.run(script, run.workflow(), raw);
        if (!happened.problems().isEmpty())
            throw new IllegalArgumentException(
                    "the case taken from "
                            + run.id()
                            + " is not a valid test for the script: "
                            + String.join("; ", happened.problems()));
        if (!happened.failures().isEmpty())
            throw new IllegalArgumentException(
                    "the case taken from "
                            + run.id()
                            + " does not reproduce the run: "
                            + String.join("; ", happened.failures()));

        ObjectNode expect = JSON.createObjectNode().put("final", final0);
        if (options.expect() == Expect.OBSERVED) {
            ArrayNode route = expect.putArray("route");
            happened.route().forEach(route::add);
            Object result = happened.result();
            if (result != null && !"".equals(result))
                expect.set("result", JSON.valueToTree(masker.maskValue(textOf(result))));
        }
        masked.set("expect", expect);
        CaseProbe.Result after = CaseProbe.run(script, run.workflow(), masked);
        if (!after.problems().isEmpty())
            throw new IllegalArgumentException(
                    "the masked case is not valid: " + String.join("; ", after.problems()));
        if (!after.route().equals(happened.route()) || !after.status().equals(happened.status())) {
            String field = fieldThatMattered(script, run, raw, masked, happened.route());
            throw new IllegalArgumentException(
                    "masking changes the way "
                            + run.id()
                            + " goes through the script (it depends on "
                            + field
                            + "). Keep that value with --mask-keep "
                            + keepName(field)
                            + " (and take the responsibility for what it holds), or edit the case.");
        }
        if (!after.failures().isEmpty())
            throw new IllegalArgumentException(
                    "the masked case does not pass: " + String.join("; ", after.failures()));

        List<String> kinds = new ArrayList<>(new java.util.TreeSet<>(masker.kinds()));
        ObjectNode file = JSON.createObjectNode();
        Path dir =
                options.to() != null ? options.to() : script.getParent().resolve("tests/captured");
        Path target =
                dir.resolve(
                        options.name() == null || options.name().isBlank()
                                ? fileName(run, loaded)
                                : options.name().replaceAll("[^A-Za-z0-9._-]+", "-")
                                        + ".test.yaml");
        ObjectNode captured = file.putObject("captured");
        captured.put("from", "run " + run.id());
        captured.put("script", script.getFileName().toString());
        captured.put("scriptHash", "sha256:" + now);
        captured.put("at", run.modified().toString());
        captured.put("by", "weave capture");
        captured.put("status", "observed");
        if (options.note() != null && !options.note().isBlank())
            captured.put("note", masker.mask(options.note()));
        ArrayNode maskedKinds = captured.putArray("masked");
        kinds.forEach(maskedKinds::add);
        Path relScript =
                target.toAbsolutePath()
                        .normalize()
                        .getParent()
                        .relativize(script.toAbsolutePath().normalize());
        file.put("script", relScript.toString().replace('\\', '/'));
        file.put("workflow", run.workflow());
        file.putArray("cases").add(masked);
        String text =
                "# Taken from a real run by weave capture; edit freely. It is a test: weave test runs it.\n"
                        + YAML.writeValueAsString(file);

        boolean unchanged = false;
        boolean conflict = false;
        boolean written = false;
        if (Files.exists(target)) {
            String existing = Files.readString(target, StandardCharsets.UTF_8);
            if (existing.equals(text)) unchanged = true;
            else conflict = true;
        } else if (write) {
            Files.createDirectories(dir);
            Files.writeString(target, text, StandardCharsets.UTF_8);
            written = true;
        }
        return new Captured(target, text, written, unchanged, conflict, kinds);
    }

    // ---- what the journal holds, by collaborator

    private record Collected(
            Map<String, List<Object>> models,
            List<String> humans,
            Map<String, List<Object>> tasks,
            Map<String, ObjectNode> failures) {}

    private static Collected collect(
            LoomScript script, ObserveReplay.Result replay, RunRecord run) {
        Map<String, List<Object>> models = new LinkedHashMap<>();
        Map<String, List<Object>> tasks = new LinkedHashMap<>();
        List<String> humans = new ArrayList<>();
        Map<String, List<Integer>> failedCalls = new LinkedHashMap<>();
        Map<String, Integer> calls = new LinkedHashMap<>();
        for (String id : replay.steps()) {
            Statement st = replay.statements().get(id);
            RunJournal.Entry e = replay.journal().get(id);
            if (st == null) continue;
            if (st instanceof ParallelStmt
                    || st instanceof BroadcastStmt
                    || (st instanceof ForEachStmt f && f.isParallel()))
                throw new IllegalArgumentException(
                        run.id()
                                + " has parallel or broadcast steps, whose calls come in no fixed"
                                + " order; a case cannot be taken from it yet.");
            if (e == null) continue;
            String agent = null;
            if (st instanceof DelegateStmt d) agent = d.getTargetAgent();
            else if (st instanceof HandoffStmt h) agent = h.getTargetAgent();
            if (agent != null) {
                int attempts = 1;
                if (st instanceof DelegateStmt d2) attempts = d2.getRetryCount() + 1;
                if (e.kind().equals("failed")) {
                    int base = calls.getOrDefault(agent, 0);
                    for (int k = 1; k <= attempts; k++)
                        failedCalls.computeIfAbsent(agent, a -> new ArrayList<>()).add(base + k);
                    calls.put(agent, base + attempts);
                } else {
                    calls.merge(agent, 1, Integer::sum);
                    models.computeIfAbsent(agent, a -> new ArrayList<>()).add(e.value());
                }
            } else if (st instanceof IntentClassifyStmt c) {
                IntentClassifierDef def =
                        script.getIntentClassifiers().stream()
                                .filter(x -> x.getName().equals(c.getClassifier()))
                                .findFirst()
                                .orElse(null);
                models.computeIfAbsent(c.getClassifier(), a -> new ArrayList<>())
                        .add(classifierAnswer(def, e.value()));
            } else if (st instanceof HumanPromptStmt && e.kind().equals("human")) {
                humans.add(String.valueOf(e.value()));
            } else if (st instanceof RunStmt r && !e.kind().equals("failed")) {
                tasks.computeIfAbsent(r.getTaskName(), a -> new ArrayList<>()).add(e.value());
            }
        }
        Map<String, ObjectNode> failures = new LinkedHashMap<>();
        failedCalls.forEach(
                (agent, list) -> {
                    ObjectNode f = JSON.createObjectNode();
                    ArrayNode on = f.putArray("on_calls");
                    list.forEach(on::add);
                    f.put("category", "server_error");
                    failures.put(agent, f);
                });
        return new Collected(models, humans, tasks, failures);
    }

    /**
     * The answers of a classifier as a case scripts them: a label per question, and the sureness.
     */
    private static Object classifierAnswer(IntentClassifierDef def, Object value) {
        if (!(value instanceof Map<?, ?> m)) return value;
        Map<String, Object> out = new LinkedHashMap<>();
        if (def != null)
            for (var q : def.getQuestions())
                if (m.containsKey(q.name())) out.put(q.name(), m.get(q.name()));
        if (def == null || out.isEmpty())
            m.forEach(
                    (k, v) -> {
                        String key = String.valueOf(k);
                        if (!key.endsWith("_confidence")
                                && !key.endsWith("_probability")
                                && !Set.of("calibrated", "backend", "model", "confidence")
                                        .contains(key)) out.put(key, v);
                    });
        if (m.get("confidence") instanceof Number n) out.put("confidence", n);
        return out;
    }

    // ---- the case

    private static ObjectNode body(
            RunRecord run, Collected c, ObserveReplay.Result replay, Options options, Masker m) {
        ObjectNode body = JSON.createObjectNode();
        body.put("name", "captured " + run.id());
        ArrayNode tags = body.putArray("tags");
        tags.add("captured");
        options.tags().forEach(tags::add);
        ObjectNode input = body.putObject("input");
        run.inputs()
                .forEach(
                        (k, v) -> {
                            if (options.denyFields().contains(k)) input.put(k, "‹denied›");
                            else if (m == null || options.maskKeep().contains(k)) input.put(k, v);
                            else input.put(k, m.mask(v));
                        });
        if (!c.models().isEmpty() || !c.humans().isEmpty() || !c.tasks().isEmpty()) {
            ObjectNode models = body.putObject("models");
            c.models()
                    .forEach(
                            (agent, list) ->
                                    models.set(
                                            agent,
                                            scripted(
                                                    list,
                                                    m == null || options.maskKeep().contains(agent)
                                                            ? null
                                                            : m)));
            if (models.isEmpty()) body.remove("models");
            if (!c.humans().isEmpty()) {
                ArrayNode humans = body.putArray("humans");
                c.humans().forEach(h -> humans.add(m == null ? h : m.mask(h)));
            }
            if (!c.tasks().isEmpty()) {
                ObjectNode tasks = body.putObject("tasks");
                c.tasks().forEach((t, list) -> tasks.set(t, scripted(list, m)));
            }
        }
        if (!c.failures().isEmpty()) {
            ObjectNode fail = body.putObject("fail");
            c.failures().forEach(fail::set);
        }
        return body;
    }

    /** One reply as it is, several as a list in order. */
    private static JsonNode scripted(List<Object> list, Masker m) {
        List<Object> values = new ArrayList<>();
        for (Object o : list) values.add(m == null ? o : m.maskValue(o));
        return values.size() == 1 ? JSON.valueToTree(values.get(0)) : JSON.valueToTree(values);
    }

    private static Object textOf(Object result) {
        return result instanceof String || result instanceof Map ? result : String.valueOf(result);
    }

    private static String finalOf(String status) {
        return switch (status) {
            case "completed", "failed", "suspended", "halted", "stopped", "budget" -> status;
            default -> "completed";
        };
    }

    /** The first input, agent or person whose masking changed the way the run goes. */
    private static String fieldThatMattered(
            Path script, RunRecord run, ObjectNode raw, ObjectNode masked, List<String> route)
            throws Exception {
        List<String> names = new ArrayList<>();
        raw.path("input").fieldNames().forEachRemaining(n -> names.add("input " + n));
        raw.path("models").fieldNames().forEachRemaining(n -> names.add("the answers of " + n));
        if (raw.has("humans")) names.add("what the people answered");
        for (String name : names) {
            ObjectNode trial = masked.deepCopy();
            if (name.startsWith("input "))
                ((ObjectNode) trial.get("input"))
                        .set(name.substring(6), raw.get("input").get(name.substring(6)));
            else if (name.startsWith("the answers of "))
                ((ObjectNode) trial.get("models"))
                        .set(name.substring(15), raw.get("models").get(name.substring(15)));
            else trial.set("humans", raw.get("humans"));
            ObjectNode e = JSON.createObjectNode();
            trial.set("expect", e);
            CaseProbe.Result r = CaseProbe.run(script, run.workflow(), trial);
            if (r.problems().isEmpty() && r.route().equals(route)) return name;
        }
        return "a masked value";
    }

    /** The name {@code --mask-keep} takes for a field the message described. */
    private static String keepName(String described) {
        if (described.startsWith("input ")) return described.substring(6);
        if (described.startsWith("the answers of ")) return described.substring(15);
        return "humans";
    }

    /** {@code <workflow>-<runhash4>-<date>.test.yaml}: the same run always gives the same name. */
    static String fileName(RunRecord run, LoomScript script) {
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(run.id().getBytes(StandardCharsets.UTF_8)))
                            .substring(0, 4);
            String date = run.modified().atZone(ZoneOffset.UTC).toLocalDate().toString();
            return run.workflow().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                    + "-"
                    + hash
                    + "-"
                    + date
                    + ".test.yaml";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of the collaborators in a captured file, for tests. */
    static Set<String> collaborators(JsonNode body) {
        Set<String> out = new LinkedHashSet<>();
        body.path("models").fieldNames().forEachRemaining(out::add);
        return out;
    }

    /** Reads the lines of a case file, for the CLI's printing. */
    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
