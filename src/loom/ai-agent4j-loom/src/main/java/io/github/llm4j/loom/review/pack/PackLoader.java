package io.github.llm4j.loom.review.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.RuleParser;
import io.github.llm4j.loom.review.rule.RuleValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads a rule pack from the jar or from a folder and refuses it, with every problem listed, unless
 * it is sound: a valid manifest, rules that parse, rules that fit the fact model, ids that belong
 * to the pack, no duplicate ids, and a firing and a quiet example for every rule. Nothing is
 * fetched from anywhere else, and nothing in a pack is run.
 */
public final class PackLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> MANIFEST_KEYS =
            List.of(
                    "name",
                    "version",
                    "factModel",
                    "rulesSchema",
                    "prefix",
                    "description",
                    "rules");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9]{1,7}");
    private static final Pattern CORE_ID = Pattern.compile("L[A-Z]\\d{2,3}");

    /** The most rules one pack may hold. */
    public static final int MAX_RULES = 500;

    /** The rule document format version this engine reads. */
    public static final int RULES_SCHEMA = 1;

    private PackLoader() {}

    /** The pack stored as resources under {@code base} (such as {@code review-rules/core}). */
    public static Pack classpath(ClassLoader loader, String base) {
        PackFiles probe = new ClasspathFiles(loader, base, List.of());
        PackManifest manifest = manifest(probe);
        return load(manifest, new ClasspathFiles(loader, base, manifest.ruleFiles()));
    }

    /** The pack in a folder. */
    public static Pack directory(Path dir) {
        return load(manifest(new DirectoryFiles(dir)), new DirectoryFiles(dir));
    }

    private static PackManifest manifest(PackFiles files) {
        String where = files.where() + "/pack.yaml";
        String text =
                files.read("pack.yaml")
                        .orElseThrow(
                                () ->
                                        new PackException(
                                                files.where(), List.of("there is no pack.yaml")));
        JsonNode n;
        try {
            n = YAML.readTree(text);
        } catch (Exception e) {
            throw new PackException(
                    where, List.of("is not valid YAML: " + firstLine(e.getMessage())));
        }
        List<String> problems = new ArrayList<>();
        if (n == null || !n.isObject())
            throw new PackException(
                    where,
                    List.of("pack.yaml is a map with name, version, factModel and rulesSchema"));
        n.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!MANIFEST_KEYS.contains(k))
                                problems.add(
                                        "unknown key '" + k + "'" + Suggest.hint(k, MANIFEST_KEYS));
                        });
        String name = text(n, "name");
        if (name == null || !NAME.matcher(name).matches())
            problems.add("name is lower case letters, digits and hyphens");
        String version = text(n, "version");
        if (version == null || !VERSION.matcher(version).matches())
            problems.add("version is major.minor.patch, such as 1.0.0");
        int factModel = n.path("factModel").asInt(0);
        if (factModel < 1 || factModel > FactModel.VERSION)
            problems.add(
                    "factModel "
                            + (factModel == 0 ? "is required" : factModel + " is not supported")
                            + "; this engine has fact model "
                            + FactModel.VERSION);
        int schema = n.path("rulesSchema").asInt(0);
        if (schema != RULES_SCHEMA) problems.add("rulesSchema must be " + RULES_SCHEMA);
        String prefix = text(n, "prefix");
        boolean core = PackManifest.CORE.equals(name);
        if (!core
                && (prefix == null
                        || !PREFIX.matcher(prefix).matches()
                        || prefix.matches("L[A-Z]")))
            problems.add(
                    "a pack other than core needs a prefix of 2 to 8 capital letters or digits that does not look like a core id (such as ACME)");
        List<String> ruleFiles = new ArrayList<>();
        if (n.has("rules")) {
            if (!n.get("rules").isArray()) problems.add("rules is a list of file paths");
            else for (JsonNode f : n.get("rules")) ruleFiles.add(f.asText());
        }
        if (!problems.isEmpty()) throw new PackException(where, problems);
        return new PackManifest(
                name,
                version,
                factModel,
                schema,
                core ? "L" : prefix,
                text(n, "description") == null ? "" : text(n, "description"),
                ruleFiles);
    }

    private static Pack load(PackManifest manifest, PackFiles files) {
        List<String> problems = new ArrayList<>();
        List<RuleDoc> rules = new ArrayList<>();
        List<String> paths =
                manifest.ruleFiles().isEmpty() ? files.ruleFiles() : manifest.ruleFiles();
        if (paths.isEmpty())
            problems.add(
                    "the pack has no rule files (list them under rules: in pack.yaml, or put them under rules/)");
        FactModel model = FactModel.v1();
        for (String path : paths) {
            String text = files.read(path).orElse(null);
            if (text == null) {
                problems.add(path + ": the file is missing or outside the pack");
                continue;
            }
            JsonNode root;
            try {
                root = YAML.readTree(text);
            } catch (Exception e) {
                problems.add(path + ": is not valid YAML: " + firstLine(e.getMessage()));
                continue;
            }
            RuleParser.Parsed parsed = RuleParser.parse(root, path);
            problems.addAll(parsed.problems());
            for (RuleDoc r : parsed.rules()) {
                List<String> bad = RuleValidator.validate(r, model);
                problems.addAll(bad.stream().map(b -> path + ": " + b).toList());
                if (bad.isEmpty()) rules.add(r.inPack(manifest.label()));
            }
        }
        problems.addAll(idProblems(manifest, rules));
        if (rules.size() > MAX_RULES) problems.add("a pack holds at most " + MAX_RULES + " rules");
        if (!problems.isEmpty()) throw new PackException(files.where(), problems);
        return new Pack(manifest, rules, files);
    }

    private static List<String> idProblems(PackManifest m, List<RuleDoc> rules) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RuleDoc r : rules) {
            if (!seen.add(r.id())) problems.add(r.id() + ": the id is used twice in this pack");
            if (m.core()) {
                if (!CORE_ID.matcher(r.id()).matches())
                    problems.add(
                            r.id() + ": a core rule id is L, a letter and two or three digits");
                else if (r.id().startsWith("LA") || r.id().startsWith("LV"))
                    problems.add(
                            r.id()
                                    + ": LA and LV ids belong to the Java audit and validator adapters");
            } else if (!r.id().matches(Pattern.quote(m.prefix()) + "-\\d{2,4}")) {
                problems.add(
                        r.id() + ": ids of pack " + m.name() + " look like " + m.prefix() + "-001");
            }
        }
        return problems;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
