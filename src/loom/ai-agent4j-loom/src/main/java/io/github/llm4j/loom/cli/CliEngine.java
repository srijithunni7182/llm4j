package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.execution.Buffers;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.RpcError;
import io.github.llm4j.loom.serve.Server;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        m.put("loom/review", this::review);
        m.put("loom/explainRule", this::explainRule);
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

    // ---- running and caching

    private record Captured(int status, String stdout, String stderr) {}

    private interface Command {
        int run(WeaveEnv env) throws Exception;
    }

    private static Captured run(Command command) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Function<String, String> noEnv = k -> null;
        WeaveEnv env =
                new WeaveEnv(
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
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        noEnv);
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
