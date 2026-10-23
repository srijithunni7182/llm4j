package io.github.llm4j.loom.review.tools;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.review.Reviews;
import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.fact.Entity;
import io.github.llm4j.loom.review.fact.EntityDef;
import io.github.llm4j.loom.review.fact.FactBase;
import io.github.llm4j.loom.review.fact.FactBuilder;
import io.github.llm4j.loom.review.fact.FactModel;
import io.github.llm4j.loom.review.fact.FieldDef;
import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackException;
import io.github.llm4j.loom.review.pack.PackHash;
import io.github.llm4j.loom.review.pack.PackLoader;
import io.github.llm4j.loom.review.pack.PackSet;
import io.github.llm4j.loom.review.pack.PackTester;
import io.github.llm4j.loom.review.rule.Evaluation;
import io.github.llm4j.loom.review.rule.Evaluator;
import io.github.llm4j.loom.review.rule.RuleDoc;
import io.github.llm4j.loom.review.rule.Tri;
import io.github.llm4j.loom.review.settings.InvalidSettingsException;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What {@code weave rules} does, apart from the command line: list the rules in force, print the
 * fact catalog, check and test a pack, scaffold a rule, copy a pack in and pin it, and explain why
 * a rule did or did not fire at one place. Everything is offline and none of it runs a script.
 *
 * <p>Each method returns the exit code: 0 done, 1 the pack or test is not sound, 2 bad input.
 */
public final class RulesTool {

    private static final Pattern RULE_ID =
            Pattern.compile("[A-Z][A-Z0-9]{1,7}-\\d{2,4}|L[A-Z]\\d{2,3}");

    private final PrintStream out;
    private final PrintStream err;

    public RulesTool(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    // ---- list

    /** The packs in force for a project folder, and each of their rules. */
    public int list(Path base) {
        ReviewSettings settings;
        PackSet packs;
        try {
            settings = settings(base);
            packs = Reviews.packs(settings, base);
        } catch (InvalidSettingsException e) {
            e.problems().forEach(p -> err.println("Error: " + p));
            return 2;
        } catch (PackException e) {
            e.problems().forEach(p -> err.println("Error: " + p));
            return 2;
        }
        for (Pack p : packs.packs()) {
            out.println(p.label() + "  (" + p.rules().size() + " rules)");
            for (RuleDoc r : p.rules()) {
                out.println(
                        "  "
                                + String.format("%-9s", r.id())
                                + String.format("%-16s", r.lens().title())
                                + String.format("%-8s", r.severity().word())
                                + state(r, settings)
                                + "  "
                                + r.title().replaceAll("\\{[^}]*\\}", "…"));
            }
        }
        out.println();
        out.println(
                "Also built in: LA01 to LA20 (security audit, Java) and LV00 (validity, Java). See weave review --explain.");
        return 0;
    }

    private static String state(RuleDoc r, ReviewSettings s) {
        Boolean forced = s.rule(r.id()).enabled();
        if (Boolean.FALSE.equals(forced)) return "off (project)";
        if (!s.lensOn(r.lens())) return "off (lens)";
        if (r.heuristic() && !s.heuristics() && !Boolean.TRUE.equals(forced))
            return "off (heuristic)";
        return "on";
    }

    // ---- facts

    /** The fact catalog rules are written against. */
    public int facts() {
        FactModel m = FactModel.v1();
        out.println("Fact model " + FactModel.VERSION + ". Rules read these and nothing else.");
        for (EntityDef e : m.entities()) {
            out.println();
            out.println(e.kind() + "  " + e.description());
            for (FieldDef f : e.fields()) {
                String type =
                        switch (f.type()) {
                            case ENUM -> "one of " + String.join("|", f.values());
                            case ENTITY -> "a " + f.entity();
                            case ENTITY_LIST -> "list of " + f.entity();
                            default -> f.type().name().toLowerCase(Locale.ROOT);
                        };
                out.println(
                        "  "
                                + String.format("%-20s", f.name())
                                + String.format("%-28s", type)
                                + f.description());
            }
        }
        out.println();
        out.println(
                "setting.<key>  any value declared in review.properties (data.*, assume.*, blast.*, workflow.*); not declared means unknown.");
        return 0;
    }

    // ---- check and test

    /** Validates a pack folder, or the bundled pack when {@code target} is {@code core}. */
    public int check(String target) {
        Pack pack = load(target);
        if (pack == null) return 1;
        out.println(
                pack.label()
                        + " is sound: "
                        + pack.rules().size()
                        + " rules, every one fits the fact model and has examples.");
        out.println("sha256:" + PackHash.of(pack.files()));
        return 0;
    }

    /** Runs the examples of every rule in a pack. */
    public int test(String target) {
        Pack pack = load(target);
        if (pack == null) return 1;
        List<PackTester.Result> results = PackTester.run(pack);
        long failed = results.stream().filter(r -> !r.passed()).count();
        for (PackTester.Result r : results)
            out.println(
                    (r.passed() ? "pass  " : "FAIL  ")
                            + r.rule()
                            + "  "
                            + r.kind()
                            + "  "
                            + r.example()
                            + (r.passed() ? "" : "  " + r.message()));
        out.println();
        out.println(
                results.size()
                        + " examples, "
                        + (results.size() - failed)
                        + " passed, "
                        + failed
                        + " failed.");
        return failed == 0 ? 0 : 1;
    }

    private Pack load(String target) {
        try {
            if (target.equals("core")) return Reviews.corePack();
            return PackLoader.directory(Path.of(target));
        } catch (PackException e) {
            err.println("Error: " + target + " is not a sound pack:");
            e.problems().forEach(p -> err.println("  - " + p));
            return null;
        }
    }

    // ---- new

    /** Adds the skeleton of a rule and its two example files to a pack folder. */
    public int scaffold(String id, Path packDir) {
        if (!RULE_ID.matcher(id).matches()) {
            err.println(
                    "Error: '"
                            + id
                            + "' is not a rule id. A pack's rules look like ACME-001 (its prefix, a hyphen and digits).");
            return 2;
        }
        if (!Files.isRegularFile(packDir.resolve("pack.yaml"))) {
            err.println(
                    "Error: "
                            + packDir
                            + " has no pack.yaml. Create the pack first (name, version, factModel: 1, rulesSchema: 1, prefix).");
            return 2;
        }
        Path rule = packDir.resolve("rules").resolve(id.toLowerCase(Locale.ROOT) + ".yaml");
        if (Files.exists(rule)) {
            err.println("Error: " + rule + " already exists; nothing was changed.");
            return 2;
        }
        String stem = id.toLowerCase(Locale.ROOT);
        try {
            Files.createDirectories(rule.getParent());
            Files.createDirectories(packDir.resolve("examples"));
            Files.writeString(rule, ruleSkeleton(id, stem));
            Files.writeString(packDir.resolve("examples/" + stem + "-fires.loom"), FIRES);
            Files.writeString(packDir.resolve("examples/" + stem + "-quiet.loom"), QUIET);
        } catch (IOException e) {
            err.println("Error: could not write the rule: " + e.getMessage());
            return 2;
        }
        out.println("Wrote " + rule);
        out.println("Wrote " + packDir.resolve("examples/" + stem + "-fires.loom"));
        out.println("Wrote " + packDir.resolve("examples/" + stem + "-quiet.loom"));
        out.println("Edit the condition and the two examples, then: weave rules test " + packDir);
        return 0;
    }

    private static final String FIRES =
            "agent Helper { model: \"m\"  system: \"s\" }\n"
                    + "workflow Main(q) { delegate \"{q}\" to Helper -> r  note \"{r}\" }\n";
    private static final String QUIET =
            "agent Helper { model: \"m\"  system: \"s\" }\n"
                    + "workflow Main(q) { note \"nothing to delegate\" }\n";

    private static String ruleSkeleton(String id, String stem) {
        return "id: "
                + id
                + "\nlens: maintainability\nseverity: low\nabout: step\nwhen:\n  field: kind\n  is: delegate\n"
                + "title: \"{label} delegates (replace this with what is wrong)\"\n"
                + "why: \"Say why it matters, in one sentence.\"\n"
                + "fixHint: \"Say what to change, in one sentence.\"\n"
                + "examples:\n  fires:\n    - { file: examples/"
                + stem
                + "-fires.loom }\n  quiet:\n    - { file: examples/"
                + stem
                + "-quiet.loom }\n";
    }

    // ---- add

    /** Copies a pack folder into {@code base/review-rules/<name>} and pins it. */
    public int add(Path source, Path base) {
        Pack pack;
        try {
            pack = PackLoader.directory(source);
        } catch (PackException e) {
            err.println("Error: " + source + " is not a sound pack, so it was not added:");
            e.problems().forEach(p -> err.println("  - " + p));
            return 1;
        }
        String name = pack.manifest().name();
        if (name.equals("core")) {
            err.println("Error: 'core' is the pack that ships with weave and cannot be replaced.");
            return 2;
        }
        Path target = base.resolve(PackSet.FOLDER).resolve(name);
        try {
            diff(pack, target);
            copy(source, target);
            String pin =
                    name + "@" + pack.manifest().version() + " sha256:" + PackHash.of(pack.files());
            pin(base.resolve(ReviewSettings.FILE_NAME), name, pin);
            out.println("Pinned in " + ReviewSettings.FILE_NAME + ": " + pin);
        } catch (IOException e) {
            err.println("Error: could not add the pack: " + e.getMessage());
            return 2;
        }
        out.println("Review the change in version control, then commit it.");
        return 0;
    }

    private void diff(Pack incoming, Path target) throws IOException {
        List<String> now = incoming.files().allFiles();
        if (!Files.isDirectory(target)) {
            out.println("Adding " + incoming.label() + " (" + now.size() + " files, new).");
            return;
        }
        Pack before;
        try {
            before = PackLoader.directory(target);
        } catch (PackException e) {
            out.println("Replacing " + target + " (the current copy is not sound).");
            return;
        }
        List<String> old = before.files().allFiles();
        out.println("Replacing " + before.label() + " with " + incoming.label() + ":");
        for (String f : now) {
            if (!old.contains(f)) out.println("  added    " + f);
            else if (!before.files().read(f).equals(incoming.files().read(f)))
                out.println("  changed  " + f);
        }
        for (String f : old) if (!now.contains(f)) out.println("  removed  " + f);
        List<String> oldRules = before.rules().stream().map(RuleDoc::id).toList();
        List<String> newRules = incoming.rules().stream().map(RuleDoc::id).toList();
        for (String id : newRules) if (!oldRules.contains(id)) out.println("  new rule " + id);
        for (String id : oldRules) if (!newRules.contains(id)) out.println("  gone     " + id);
    }

    private static void copy(Path source, Path target) throws IOException {
        if (Files.isDirectory(target)) delete(target);
        Path src = source.toAbsolutePath().normalize();
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p :
                    s.filter(Files::isRegularFile).filter(f -> !Files.isSymbolicLink(f)).toList()) {
                Path to = target.resolve(src.relativize(p).toString());
                Files.createDirectories(to.getParent());
                Files.copy(p, to);
            }
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** Sets this pack's entry in {@code packs =}, keeping the other entries and every comment. */
    static void pin(Path file, String name, String entry) throws IOException {
        List<String> lines =
                Files.isRegularFile(file)
                        ? new ArrayList<>(Files.readAllLines(file))
                        : new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!l.stripLeading().startsWith("packs")) continue;
            int eq = l.indexOf('=');
            if (eq < 0 || !l.substring(0, eq).strip().equals("packs")) continue;
            String value = l.substring(eq + 1);
            String comment = "";
            int hash = value.indexOf('#');
            if (hash >= 0) {
                comment = "   " + value.substring(hash);
                value = value.substring(0, hash);
            }
            List<String> entries = new ArrayList<>();
            boolean replaced = false;
            for (String e : value.split(",")) {
                String t = e.strip();
                if (t.isEmpty()) continue;
                if (t.startsWith(name + "@")) {
                    entries.add(entry);
                    replaced = true;
                } else entries.add(t);
            }
            if (!replaced) entries.add(entry);
            lines.set(i, "packs = " + String.join(", ", entries) + comment);
            Files.write(file, lines);
            return;
        }
        lines.add("packs = core@1, " + entry);
        Files.write(file, lines);
    }

    // ---- explain --at

    /**
     * Evaluates one rule at one place of one script and prints every condition with the value it
     * saw, so it is clear why the rule did or did not fire there.
     *
     * @param at {@code file:line}
     */
    public int explainAt(String ruleId, String at) {
        int colon = at.lastIndexOf(':');
        int line;
        try {
            line = Integer.parseInt(at.substring(colon + 1));
            if (colon < 1 || line < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            err.println("Error: --at takes file:line, such as main.loom:42.");
            return 2;
        }
        Path script = Path.of(at.substring(0, colon)).toAbsolutePath();
        if (!Files.isRegularFile(script)) {
            err.println("Error: " + script + " does not exist.");
            return 2;
        }
        Path base = script.getParent();
        ReviewSettings settings;
        PackSet packs;
        try {
            settings = ReviewSettings.besides(script);
            packs = Reviews.packs(settings, base);
        } catch (InvalidSettingsException | PackException e) {
            List<String> problems =
                    e instanceof InvalidSettingsException i
                            ? i.problems()
                            : ((PackException) e).problems();
            problems.forEach(p -> err.println("Error: " + p));
            return 2;
        }
        Optional<RuleDoc> rule =
                packs.packs().stream()
                        .flatMap(p -> p.rules().stream())
                        .filter(r -> r.id().equalsIgnoreCase(ruleId))
                        .findFirst();
        if (rule.isEmpty()) {
            List<String> ids =
                    packs.packs().stream()
                            .flatMap(p -> p.rules().stream())
                            .map(RuleDoc::id)
                            .toList();
            err.println(
                    "Error: no data rule "
                            + ruleId
                            + Suggest.hint(ruleId.toUpperCase(Locale.ROOT), ids)
                            + ". (LA and LV rules are Java and have no condition to show.)");
            return 2;
        }
        FactBase base2;
        try {
            LoomScript loaded = new LoomLoader().load(script.toString());
            GraphResult graph = graph(script);
            base2 =
                    FactBuilder.standard()
                            .build(
                                    new ReviewInput(
                                            script.getFileName().toString(),
                                            base,
                                            loaded,
                                            Map.of(),
                                            List.of(),
                                            graph),
                                    settings);
        } catch (Exception e) {
            err.println("Error: " + script + " could not be read: " + e.getMessage());
            return 2;
        }
        RuleDoc r = rule.get();
        Entity target = nearest(base2.entities(r.about()), line);
        if (target == null) {
            err.println(
                    "Error: the script has no "
                            + r.about()
                            + " for "
                            + r.id()
                            + " to look at. It runs once per "
                            + r.about()
                            + ".");
            return 2;
        }
        Evaluation result = Evaluator.evaluate(r.when(), target, base2);
        out.println(
                r.id()
                        + " at "
                        + target.id()
                        + (target.where().line() > 0
                                ? " (line " + target.where().line() + ")"
                                : ""));
        out.print(result.render());
        out.println(
                result.result() == Tri.TRUE
                        ? "So " + r.id() + " fires here."
                        : result.result() == Tri.UNKNOWN
                                ? "So "
                                        + r.id()
                                        + " does not fire here: something it needs is not known."
                                : "So " + r.id() + " does not fire here.");
        return 0;
    }

    /** The entity on {@code line}, else the closest one before it, else the first one. */
    private static Entity nearest(List<Entity> entities, int line) {
        Entity best = null;
        for (Entity e : entities) {
            int l = e.where().line();
            if (l == line) return e;
            if (l > 0 && l < line && (best == null || l > best.where().line())) best = e;
        }
        return best != null ? best : entities.isEmpty() ? null : entities.get(0);
    }

    private static GraphResult graph(Path script) {
        try {
            return new GraphService().graph(script);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ReviewSettings settings(Path base) {
        Path file = base.resolve(ReviewSettings.FILE_NAME);
        return Files.isRegularFile(file) ? ReviewSettings.load(file) : ReviewSettings.defaults();
    }
}
