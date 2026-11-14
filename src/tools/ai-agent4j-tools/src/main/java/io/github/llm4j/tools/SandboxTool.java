package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs an allow-listed program on a workspace's files in the declared isolation (D6), and keeps one
 * running for a while ({@code serve}, D7). A program is a name and an argument list, never a line
 * for a shell; its working directory stays inside the workspace; its environment is empty but for
 * the variables the script named, whose secrets are looked up for each call and never kept.
 */
final class SandboxTool extends GenericTool {

    /** Most protected files mounted one by one; a protected directory is one mount. */
    static final int MAX_PROTECTED_MOUNTS = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SandboxKind.Checked c;
    private final EffectContext context;
    private final WorkspaceJail jail;
    private final Leases leases;
    private final String owner;
    private final boolean gitWritable;

    SandboxTool(String name, SandboxKind.Checked checked, EffectContext context, boolean gitWritable) {
        super(name, "sandbox", description(checked), Redactor.NONE, context);
        this.c = checked;
        this.gitWritable = gitWritable;
        this.context = context;
        this.jail = new WorkspaceJail(checked.workspace(), Set.of());
        this.leases = new Leases(checked.store(), context.clock());
        this.owner = name;
        stopLeftovers();
    }

    private static String description(SandboxKind.Checked c) {
        return "Runs a program on the workspace's files, isolated ("
                + SandboxKind.level(c.isolation())
                + "). Give program (one of "
                + String.join(", ", c.allow())
                + "), args (a list of strings, one per argument), and optionally cwd (a folder of the workspace), stdin and"
                + " timeout (at most "
                + c.limits().timeout().toMinutes()
                + "m). Network: "
                + c.network().mode().name().toLowerCase(Locale.ROOT)
                + (c.servePort() == null ? "." : ". action serve starts it and keeps it running (it must listen on port " + c.servePort() + "); stop and status take its handle.")
                + " Returns exit, timed_out, stdout and stderr (the start and the end when long).";
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return !"status".equals(args.get("action"));
    }

    @Override
    public String target(Map<String, Object> args) {
        Object p = args.get("program");
        Object h = args.get("handle");
        return String.valueOf(args.getOrDefault("action", "run")) + " " + (p != null ? Limits.excerpt(String.valueOf(p), 60) : h != null ? h : "");
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws Exception {
        expire();
        String action = args.get("action") == null ? "run" : String.valueOf(args.get("action"));
        Map<String, Object> out =
                switch (action) {
                    case "run" -> run(args);
                    case "serve" -> serve(args);
                    case "stop" -> stop(text(args, "handle"));
                    case "status" -> status(text(args, "handle"));
                    default -> throw new ToolRefusal("action must be run, serve, stop or status, not " + action);
                };
        return JSON.writeValueAsString(out);
    }

    // ---- run

    private Map<String, Object> run(Map<String, Object> args) throws IOException, InterruptedException {
        Resolved r = request(args, "run");
        SandboxResult result = c.backend().run(r.request());
        if (result.isolation().compareTo(c.isolation()) < 0)
            throw new ToolRefusal(
                    "the backend reported isolation "
                            + SandboxKind.level(result.isolation())
                            + ", below the "
                            + SandboxKind.level(c.isolation())
                            + " the script asks for: the run is stopped and its output withheld");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exit", result.exit());
        out.put("timed_out", result.timedOut());
        out.put("isolation", SandboxKind.level(result.isolation()));
        out.put("duration_ms", result.durationMs());
        out.put("stdout", r.redactor().scrub(result.stdout()));
        out.put("stderr", r.redactor().scrub(result.stderr()));
        out.put("truncated", result.truncated());
        out.put("cut_bytes", result.cutBytes());
        return out;
    }

    /** A request checked against the allow list and the workspace, with this call's secrets. */
    private record Resolved(SandboxRequest request, Redactor redactor) {}

    private Resolved request(Map<String, Object> args, String action) throws IOException {
        String program = text(args, "program");
        if (program.contains("/") || program.contains("\\") || program.chars().anyMatch(Character::isWhitespace))
            throw new ToolRefusal("program is a name (one of " + String.join(", ", c.allow()) + "); put its arguments in args, as a list");
        if (!c.allow().contains(program))
            throw new ToolRefusal(program + " is not allowed (allowed: " + String.join(", ", c.allow()) + ")");
        List<String> argList = arguments(args.get("args"));
        String cwd = optionalText(args, "cwd");
        String relativeCwd = "";
        if (cwd != null) {
            Path dir = jail.resolve(cwd);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new ToolRefusal("cwd: " + cwd + " is not a folder of the workspace");
            relativeCwd = jail.relative(dir);
        }
        Duration timeout = c.limits().timeout();
        String asked = optionalText(args, "timeout");
        if (asked != null) {
            Duration d = Options.parseDuration(asked);
            if (d == null || d.isZero()) throw new ToolRefusal("timeout must be a duration like 30s or 5m");
            if (d.compareTo(timeout) < 0) timeout = d; // an agent may ask for less, never more
        }
        Map<String, String> env = new LinkedHashMap<>();
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> e : c.env().entrySet()) {
            String value = context.lookup(e.getValue());
            if (value == null || value.isEmpty())
                throw new ToolRefusal(e.getValue() + " is not set (for " + e.getKey() + ")");
            env.put(e.getKey(), value);
            values.add(value);
        }
        String stdin = args.get("stdin") instanceof String s ? s : null;
        String name =
                "llm4j-"
                        + action
                        + "-"
                        + CanonicalArgs.sha256Hex(context.identityStep() + "\n" + context.attempt() + "\n" + owner + "\n" + program + "\n" + argList)
                                .substring(0, 16);
        SandboxRequest request =
                new SandboxRequest(
                        program, argList, c.workspace(), relativeCwd, protectedPaths(), stdin, env, c.limits().withTimeout(timeout), c.network(), name);
        return new Resolved(request, new Redactor(values));
    }

    /**
     * The arguments: a list of strings, or (a script has no list literal) the text of a JSON list of
     * strings such as {@code ["-m", "pytest"]}. Text that is not a JSON list is refused, never split
     * like a command line.
     */
    private static List<String> arguments(Object given) {
        if (given == null) return List.of();
        if (given instanceof String text && text.strip().startsWith("[")) {
            try {
                given = JSON.readValue(text, List.class);
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                throw new ToolRefusal("args is not a JSON list of strings: " + Limits.excerpt(e.getOriginalMessage(), 120));
            }
        }
        if (!(given instanceof List<?> list))
            throw new ToolRefusal(
                    "args must be a list of strings, one per argument, or the text of one such as [\"-m\", \"pytest\"] (it is never read as a command line)");
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof CharSequence || o instanceof Number || o instanceof Boolean))
                throw new ToolRefusal("args must hold strings only");
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * The paths to mount read only: the protected globs (a covered folder once, else each file) and
     * every {@code .git} folder, unless this is the git tool's own sandbox.
     */
    private List<String> protectedPaths() throws IOException {
        if ((c.protect().isEmpty() && gitWritable) || !Files.isDirectory(c.workspace()))
            return List.of();
        Globs protect = new Globs(c.protect());
        List<String> out = new ArrayList<>();
        Files.walkFileTree(
                c.workspace(),
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(c.workspace())) return FileVisitResult.CONTINUE;
                        String relative = jail.relative(dir);
                        if ((!gitWritable && dir.getFileName().toString().equals(".git"))
                                || protect.coversDirectory(relative)) {
                            out.add(relative);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isSymbolicLink() && protect.matches(jail.relative(file))) out.add(jail.relative(file));
                        return FileVisitResult.CONTINUE;
                    }
                });
        if (out.size() > MAX_PROTECTED_MOUNTS)
            throw new ToolRefusal("protect: matches more than " + MAX_PROTECTED_MOUNTS + " paths; protect a folder instead");
        return out;
    }

    // ---- serve, stop, status: a lease per served program

    private Map<String, Object> serve(Map<String, Object> args) throws IOException, InterruptedException {
        if (c.servePort() == null) throw new ToolRefusal("this sandbox does not serve: declare serve_port: on the tool");
        List<Leases.Lease> live = leases.of(owner);
        if (live.size() >= c.maxServed())
            throw new ToolRefusal(
                    live.size() + " served programs are running (max_served: " + c.maxServed() + "); stop one first ("
                            + live.stream().map(Leases.Lease::handle).toList() + ")");
        Resolved r = request(args, "serve");
        SandboxBackend.Served served = c.backend().serve(r.request(), c.servePort());
        long deadline = context.clock().millis() + r.request().limits().timeout().toMillis();
        leases.write(new Leases.Lease(served.handle(), owner, served.hostPort(), deadline));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("handle", served.handle());
        out.put("port", served.hostPort());
        out.put("deadline", deadline);
        out.put("url", "http://127.0.0.1:" + served.hostPort() + "/");
        return out;
    }

    private Map<String, Object> stop(String handle) throws IOException, InterruptedException {
        lease(handle);
        c.backend().stop(handle);
        leases.delete(handle);
        return Map.of("handle", handle, "stopped", true);
    }

    private Map<String, Object> status(String handle) throws IOException, InterruptedException {
        Leases.Lease lease = lease(handle);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("handle", handle);
        out.put("running", c.backend().running(handle));
        out.put("deadline", lease.deadline());
        return out;
    }

    /** This tool's lease of {@code handle}, or a refusal saying there is none. */
    private Leases.Lease lease(String handle) throws IOException {
        return leases.get(handle)
                .filter(l -> l.owner().equals(owner))
                .orElseThrow(() -> new ToolRefusal("there is no served program " + handle + " (it ended, or was stopped)"));
    }

    /** Stops served programs past their deadline. */
    private void expire() throws IOException, InterruptedException {
        for (Leases.Lease l : leases.of(owner))
            if (!leases.live(l)) {
                c.backend().stop(l.handle());
                leases.delete(l.handle());
            }
    }

    /**
     * A tool is built when a run starts or resumes: a lease still on disk is one a crashed run left
     * (D7), so its program is stopped before any work is done.
     */
    private void stopLeftovers() {
        try {
            for (Leases.Lease l : leases.of(owner)) {
                c.backend().stop(l.handle());
                leases.delete(l.handle());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", owner);
                data.put("handle", l.handle());
                context.audit("served_leftover_stopped", data);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ToolRefusal("could not stop what an earlier run left running: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema =
                new LinkedHashMap<>(
                        io.github.llm4j.model.ToolSchema.object()
                                .enumeration("action", "run (the default), serve, stop or status", false, List.of("run", "serve", "stop", "status"))
                                .string("program", "The program, one of: " + String.join(", ", c.allow()), false)
                                .stringArray("args", "Its arguments, one per element", false)
                                .string("cwd", "A folder of the workspace to run in", false)
                                .string("stdin", "Text the program reads on its input", false)
                                .string("timeout", "A shorter time limit, like 30s", false)
                                .string("handle", "A served program's handle (stop, status)", false)
                                .build());
        return schema;
    }
}
