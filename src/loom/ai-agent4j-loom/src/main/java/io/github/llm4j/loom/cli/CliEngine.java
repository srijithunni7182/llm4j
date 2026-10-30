package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.execution.Buffers;
import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.fix.FixApplier;
import io.github.llm4j.loom.review.handle.AcceptWriter;
import io.github.llm4j.loom.review.handle.Baseline;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.FixSafety;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.RpcError;
import io.github.llm4j.loom.serve.Server;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import picocli.CommandLine;

/**
 * The methods of {@code weave serve}: the same code that answers {@code weave check}, {@code weave
 * graph} and {@code weave review}, called in process with its output captured, so an editor sees
 * exactly what the command would print. Nothing here runs a model, a tool, a task or a workflow,
 * loads a class of the user's, or writes a file; a script is read through {@link Buffers}, so the
 * text an editor has open and not saved is what is checked.
 */
final class CliEngine implements Server.Engine {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ResultCache cache;
    private final String version;

    CliEngine(ResultCache cache, String version) {
        this.cache = cache;
        this.version = version;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public Map<String, Method> methods() {
        Map<String, Method> m = new LinkedHashMap<>();
        m.put("loom/check", this::check);
        m.put("loom/graph", this::graph);
        m.put("loom/symbols", this::symbols);
        m.put("loom/next", this::next);
        m.put("loom/review", this::review);
        m.put("loom/explainRule", this::explainRule);
        m.put("loom/applyFix", this::applyFix);
        m.put("loom/accept", this::accept);
        m.put("loom/baseline", this::baseline);
        return m;
    }

    // ---- the methods

    private JsonNode check(Server.Request r) throws Exception {
        Path file = r.file();
        return cached(
                r,
                "check|" + file,
                file,
                List.of(),
                env -> {
                    java.io.File f = file.toFile();
                    int status =
                            WeaveCLI.check(
                                    f,
                                    null,
                                    new WeaveCLI.CheckSettings(false, true, true, false),
                                    env);
                    return status;
                },
                true);
    }

    private JsonNode graph(Server.Request r) throws Exception {
        Path file = r.file();
        String workflow = r.params().path("workflow").asText(null);
        List<String> args = new ArrayList<>(List.of(file.toString()));
        if (workflow != null) args.addAll(List.of("--workflow", workflow));
        return cached(
                r,
                "graph|" + file + "|" + workflow,
                file,
                List.of(),
                env -> {
                    GraphCommand c = new GraphCommand();
                    new CommandLine(c).parseArgs(args.toArray(String[]::new));
                    return GraphCommand.graph(c, env);
                },
                false);
    }

    private JsonNode symbols(Server.Request r) throws Exception {
        Path file = r.file();
        return cached(
                r,
                "symbols|" + file,
                file,
                List.of(),
                env -> {
                    SymbolsCommand c = new SymbolsCommand();
                    new CommandLine(c).parseArgs(file.toString());
                    return SymbolsCommand.symbols(c, env);
                },
                false);
    }

    /**
     * Where the project stands, as {@code weave next --format json} prints it. It is never cached:
     * the answer depends on many files (results recorded under {@code .loom}, the dataset, the
     * README) that the cache does not watch.
     */
    private JsonNode next(Server.Request r) throws Exception {
        Path file = r.file();
        Captured c =
                Buffers.with(
                        r.texts(),
                        new LinkedHashSet<>(),
                        () ->
                                run(
                                        env -> {
                                            NextCommand n = new NextCommand();
                                            new CommandLine(n)
                                                    .parseArgs(file.toString(), "--format", "json");
                                            return NextCommand.next(n, env);
                                        }));
        if (c.stdout.isBlank())
            throw new RpcError(
                    RpcError.ENGINE,
                    "engine-failure",
                    c.stderr.isBlank() ? "exit status " + c.status : c.stderr.strip());
        ObjectNode answer = JSON.createObjectNode();
        answer.put("file", file.toString());
        answer.put("status", c.status);
        answer.set("result", JSON.readTree(c.stdout));
        answer.put("cached", false);
        return answer;
    }

    private JsonNode review(Server.Request r) throws Exception {
        Path file = r.file();
        String tier = r.params().path("tier").asText("full");
        List<String> args =
                new ArrayList<>(List.of(file.toString(), "--format", "json", "--no-env"));
        String lenses = r.params().path("lenses").asText(null);
        if (lenses == null && tier.equals("instant")) lenses = "validity";
        if (lenses != null) args.addAll(List.of("--lens", lenses));
        if (r.params().hasNonNull("minSeverity"))
            args.addAll(List.of("--min-severity", r.params().get("minSeverity").asText()));
        List<Path> extra = new ArrayList<>();
        if (r.params().hasNonNull("prices")) {
            Path prices = r.workspace().resolve(r.params().get("prices").asText());
            args.addAll(List.of("--prices", prices.toString()));
            extra.add(prices);
        }
        if (r.params().has("assume"))
            for (JsonNode a : r.params().get("assume"))
                args.addAll(List.of("--assume", a.asText()));
        extra.add(file.resolveSibling("review.properties"));
        extra.add(file.resolveSibling("review.baseline.json"));
        return cached(
                r,
                "review|" + String.join(" ", args),
                file,
                extra,
                env -> {
                    ReviewCommand c = new ReviewCommand();
                    new CommandLine(c).parseArgs(args.toArray(String[]::new));
                    return ReviewCommand.review(c, env);
                },
                false);
    }

    private JsonNode explainRule(Server.Request r) throws Exception {
        String rule = r.params().path("rule").asText(null);
        if (rule == null || rule.isBlank())
            throw new RpcError(RpcError.INVALID_PARAMS, "invalid-params", "rule is required");
        List<String> args = new ArrayList<>(List.of("--explain", rule));
        Captured out =
                run(
                        env -> {
                            ReviewCommand c = new ReviewCommand();
                            new CommandLine(c).parseArgs(args.toArray(String[]::new));
                            return ReviewCommand.review(c, env);
                        });
        if (out.stdout.isBlank())
            throw new RpcError(RpcError.ENGINE, "engine-failure", out.stderr.strip());
        ObjectNode n = JSON.createObjectNode();
        n.put("rule", rule);
        n.put("text", out.stdout.stripTrailing());
        return n;
    }

    // ---- fixes, accepts and the baseline: the text the editor should write, never a file written
    // here

    /** A review set up for one script, as {@code weave review file --no-env} would set it up. */
    private ReviewCommand.Opened session(Server.Request r, Path file, WeaveEnv env)
            throws RpcError {
        List<String> args =
                new ArrayList<>(List.of(file.toString(), "--format", "json", "--no-env"));
        if (r.params().hasNonNull("prices")) {
            args.addAll(
                    List.of(
                            "--prices",
                            r.workspace().resolve(r.params().get("prices").asText()).toString()));
        }
        ReviewCommand c = new ReviewCommand();
        new CommandLine(c).parseArgs(args.toArray(String[]::new));
        try {
            return ReviewCommand.open(c, env);
        } catch (ReviewCommand.SetupError e) {
            throw new RpcError(RpcError.ENGINE, "engine-failure", String.join("; ", e.lines()));
        }
    }

    private static String originalText(Server.Request r, Path file) throws Exception {
        Map<Path, String> texts = r.texts();
        return texts.containsKey(file) ? texts.get(file) : Buffers.read(file);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(n -> out.add(n.asText()));
        return out;
    }

    /**
     * Fixes as new text: all the safe ones, or the named findings, or one option a person chose
     * with their answers. Every result is checked as {@code weave review --fix} checks it (the
     * script loads, no new high finding), and what cannot be applied is listed with why. The editor
     * writes the text, so undo and unsaved buffers stay right.
     */
    private JsonNode applyFix(Server.Request r) throws Exception {
        Path file = r.file();
        Map<Path, String> texts = r.texts();
        return Buffers.with(
                texts,
                new LinkedHashSet<>(),
                () ->
                        inEnv(
                                env -> {
                                    ReviewCommand.Opened opened = session(r, file, env);
                                    ReviewSession session = opened.session();
                                    String original = originalText(r, file);
                                    List<String> fingerprints =
                                            strings(r.params().get("fingerprints"));
                                    String option = r.params().path("option").asText(null);
                                    ObjectNode out = JSON.createObjectNode();
                                    out.put("file", file.toString());
                                    out.put("original", original);
                                    if (option != null) {
                                        if (fingerprints.size() != 1)
                                            throw new RpcError(
                                                    RpcError.INVALID_PARAMS,
                                                    "invalid-params",
                                                    "an option is chosen for exactly one finding");
                                        Map<String, String> answers = new LinkedHashMap<>();
                                        JsonNode given = r.params().get("answers");
                                        if (given != null && given.isObject())
                                            given.fields()
                                                    .forEachRemaining(
                                                            e ->
                                                                    answers.put(
                                                                            e.getKey(),
                                                                            e.getValue().asText()));
                                        FixApplier.Chosen chosen =
                                                FixApplier.applyOption(
                                                        original,
                                                        fingerprints.get(0),
                                                        option,
                                                        answers,
                                                        session::reviewText);
                                        if (!chosen.ok())
                                            throw new RpcError(
                                                    RpcError.INVALID_PARAMS,
                                                    "fix-refused",
                                                    chosen.error());
                                        out.put("text", chosen.text());
                                        out.put("changed", !chosen.text().equals(original));
                                        out.putArray("applied")
                                                .addObject()
                                                .put("option", option)
                                                .put("fingerprint", fingerprints.get(0));
                                        out.putArray("withheld");
                                        return out;
                                    }
                                    boolean safe = r.params().path("safe").asBoolean(false);
                                    if (!safe && fingerprints.isEmpty())
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                "invalid-params",
                                                "name the findings to fix, or ask for the safe ones");
                                    Set<String> wanted = new LinkedHashSet<>(fingerprints);
                                    FixApplier.Result result =
                                            FixApplier.run(
                                                    original,
                                                    f ->
                                                            f.fix().isPresent()
                                                                    && (safe
                                                                            ? f.fix().get().safety()
                                                                                    == FixSafety
                                                                                            .SAFE
                                                                            : wanted.contains(
                                                                                    f
                                                                                            .fingerprint())),
                                                    session::reviewText);
                                    out.put("text", result.text());
                                    out.put("changed", result.changed(original));
                                    ArrayNode applied = out.putArray("applied");
                                    for (FixApplier.Applied a : result.applied())
                                        applied.addObject()
                                                .put("rule", a.rule())
                                                .put("line", a.line())
                                                .put("label", a.label())
                                                .put("pass", a.pass());
                                    ArrayNode withheld = out.putArray("withheld");
                                    result.withheld().forEach(withheld::add);
                                    return out;
                                }));
    }

    /**
     * Accepting findings: the lines to add to {@code review.properties} and the file's text after
     * them. A reason is required, the lethal trifecta is refused here (it belongs in the script),
     * and accepting several serious findings at once needs their number typed back.
     */
    private JsonNode accept(Server.Request r) throws Exception {
        Path file = r.file();
        return Buffers.with(
                r.texts(),
                new LinkedHashSet<>(),
                () ->
                        inEnv(
                                env -> {
                                    ReviewCommand.Opened opened = session(r, file, env);
                                    List<String> fingerprints =
                                            strings(r.params().get("fingerprints"));
                                    if (fingerprints.isEmpty())
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                "invalid-params",
                                                "name the findings to accept");
                                    AcceptWriter.Scope scope;
                                    try {
                                        scope =
                                                AcceptWriter.Scope.valueOf(
                                                        r.params()
                                                                .path("scope")
                                                                .asText("finding")
                                                                .toUpperCase(Locale.ROOT));
                                    } catch (IllegalArgumentException e) {
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                "invalid-params",
                                                "scope is finding, thing, script or project");
                                    }
                                    LocalDate until = null;
                                    if (r.params().hasNonNull("until")) {
                                        try {
                                            until =
                                                    LocalDate.parse(
                                                            r.params().get("until").asText());
                                        } catch (RuntimeException e) {
                                            throw new RpcError(
                                                    RpcError.INVALID_PARAMS,
                                                    "invalid-params",
                                                    "until is a date such as 2026-12-31");
                                        }
                                    }
                                    ReviewReport report = opened.session().review(file);
                                    List<Finding> targets = new ArrayList<>();
                                    for (Finding f : report.findings())
                                        if (fingerprints.contains(f.fingerprint())) targets.add(f);
                                    if (targets.isEmpty())
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                "no-such-finding",
                                                "no open finding has these fingerprints");
                                    if (targets.size() > 1
                                            && targets.stream().anyMatch(AcceptWriter::serious)
                                            && r.params().path("confirm").asInt(-1)
                                                    != targets.size())
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                "confirm-count",
                                                "this would accept "
                                                        + targets.size()
                                                        + " serious findings: confirm the number");
                                    String by =
                                            r.params()
                                                    .path("by")
                                                    .asText(System.getProperty("user.name", ""));
                                    String on = r.params().path("on").asText(null);
                                    List<String> lines = new ArrayList<>();
                                    try {
                                        for (Finding f : targets)
                                            lines.add(
                                                    AcceptWriter.line(
                                                            f,
                                                            scope,
                                                            ReviewHandling.thing(on, f),
                                                            r.params().path("reason").asText(null),
                                                            until,
                                                            by,
                                                            opened.today()));
                                    } catch (IllegalArgumentException e) {
                                        throw new RpcError(
                                                RpcError.INVALID_PARAMS,
                                                e.getMessage().startsWith("LA01")
                                                        ? "trifecta-in-script"
                                                        : "accept-refused",
                                                e.getMessage());
                                    }
                                    Path properties =
                                            opened.scriptDir().resolve(ReviewSettings.FILE_NAME);
                                    String existing =
                                            Files.isRegularFile(properties)
                                                    ? Files.readString(properties)
                                                    : "";
                                    AcceptWriter.Appended appended =
                                            AcceptWriter.appended(existing, lines);
                                    ObjectNode out = JSON.createObjectNode();
                                    out.put("file", properties.toString());
                                    out.put("existed", Files.isRegularFile(properties));
                                    out.put("original", existing);
                                    out.put("text", appended.text());
                                    out.put("written", appended.written());
                                    ArrayNode added = out.putArray("lines");
                                    lines.forEach(added::add);
                                    return out;
                                }));
    }

    /** The baseline file's text for today's open findings (what to record and what stays open). */
    private JsonNode baseline(Server.Request r) throws Exception {
        Path file = r.file();
        return Buffers.with(
                r.texts(),
                new LinkedHashSet<>(),
                () ->
                        inEnv(
                                env -> {
                                    ReviewCommand.Opened opened = session(r, file, env);
                                    ReviewReport report = opened.session().review(file);
                                    Baseline.Rendered rendered =
                                            Baseline.render(
                                                    report.findings(),
                                                    r.params()
                                                            .path("includeSerious")
                                                            .asBoolean(false),
                                                    opened.today(),
                                                    r.params()
                                                            .path("note")
                                                            .asText("recorded from the editor"));
                                    Path target = opened.baselinePath();
                                    ObjectNode out = JSON.createObjectNode();
                                    out.put("file", target.toString());
                                    out.put("existed", Files.isRegularFile(target));
                                    out.put("text", rendered.text());
                                    out.put("recorded", rendered.recorded());
                                    out.put("leftOut", rendered.leftOut());
                                    return out;
                                }));
    }

    // ---- running and caching

    private record Captured(int status, String stdout, String stderr) {}

    private interface Command {
        int run(WeaveEnv env) throws Exception;
    }

    private interface WithEnv<T> {
        T apply(WeaveEnv env) throws Exception;
    }

    /** Runs {@code work} with an environment that has no models, no people and no outside world. */
    private static <T> T inEnv(WithEnv<T> work) throws Exception {
        return work.apply(quietEnv(new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    private static WeaveEnv quietEnv(ByteArrayOutputStream out, ByteArrayOutputStream err) {
        Function<String, String> noEnv = k -> null;
        return new WeaveEnv(
                m -> {
                    throw new IllegalStateException("weave serve calls no model");
                },
                q -> {
                    throw new IllegalStateException("weave serve asks no one");
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                Clock.systemDefaultZone(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                noEnv);
    }

    private static Captured run(Command command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env = quietEnv(out, err);
        int status = command.run(env);
        return new Captured(
                status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Answers from the cache when everything the answer was made from is unchanged, else runs the
     * command (reading through the buffers) and keeps the answer. An exit status of 2 with nothing
     * on standard output is a failure to run; with {@code jsonOnError} the output is the answer
     * whatever the status (a check that finds errors is still an answer).
     */
    private JsonNode cached(
            Server.Request r,
            String key,
            Path file,
            List<Path> extraDependencies,
            Command command,
            boolean jsonOnError)
            throws Exception {
        Map<Path, String> texts = r.texts();
        var hit = cache.get(key, texts);
        if (hit.isPresent()) {
            ObjectNode copy = ((ObjectNode) hit.get()).deepCopy();
            copy.put("cached", true);
            return copy;
        }
        Set<Path> read = new LinkedHashSet<>();
        Captured c = Buffers.with(texts, read, () -> run(command));
        if (c.stdout.isBlank())
            throw new RpcError(
                    RpcError.ENGINE,
                    "engine-failure",
                    c.stderr.isBlank() ? "exit status " + c.status : c.stderr.strip());
        JsonNode parsed;
        try {
            parsed = JSON.readTree(c.stdout);
        } catch (Exception e) {
            throw new RpcError(
                    RpcError.ENGINE,
                    "engine-failure",
                    "the command's output is not JSON: " + c.stdout.strip());
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("file", file.toString());
        answer.put("status", c.status);
        answer.set("result", parsed);
        if (!c.stderr.isBlank()) answer.put("stderr", c.stderr.strip());
        answer.put("cached", false);
        Set<Path> dependencies = new LinkedHashSet<>(read);
        dependencies.add(file);
        dependencies.addAll(extraDependencies);
        dependencies.addAll(promptFiles(file));
        cache.put(key, answer, dependencies, texts);
        return answer;
    }

    /**
     * The script's {@code prompts} folder: a prompt added, removed or edited makes an answer stale.
     */
    private static List<Path> promptFiles(Path script) {
        return List.of(script.resolveSibling("prompts"));
    }
}
