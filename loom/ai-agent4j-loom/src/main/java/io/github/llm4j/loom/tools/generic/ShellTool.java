package io.github.llm4j.loom.tools.generic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs one allow-listed program with an argument list, bounded in time and output. */
final class ShellTool extends GenericTool {

    private static final int MAX_ARGS = 64;
    private static final int MAX_ARG_LENGTH = 4096;

    /** A declaration, parsed and checked. */
    record Config(Map<String, Path> programs, Path cwd, List<String> envPass, long maxOutput, Duration timeout,
                  EffectPolicy.OnUnknown onUnknown) {

        static Config parse(Options o, Path baseDir, String osName) {
            if (osName.toLowerCase(Locale.ROOT).contains("win")) throw new OptionException("use: shell is supported on Linux and macOS, not Windows");
            List<String> allow = o.list("allow");
            if (allow.isEmpty()) throw new OptionException("allow: needs at least one program name, e.g. \"git, df\"");
            Map<String, Path> programs = ShellKind.resolveAllowed(allow, o.bool("allow_interpreters", false));
            Path cwd = ShellKind.resolveCwd(o.string("cwd", "."), baseDir);
            o.bool("unattended", false);
            return new Config(programs, cwd, o.list("env_pass"), o.size("max_output", 64 * 1024, 4L * 1024 * 1024),
                    o.duration("timeout", Duration.ofSeconds(30), Duration.ofMinutes(10)),
                    EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")));
        }
    }

    private final Config config;

    ShellTool(String name, Config config, Redactor redactor, EffectContext context) {
        super(name, "shell", description(config), redactor, context);
        this.config = config;
    }

    private static String description(Config c) {
        return "Runs a program on this machine. Arguments: program (one of " + String.join(", ", c.programs().keySet())
                + "), args (a list of strings; no shell is involved, so quoting, pipes and wildcards have no effect). "
                + "Returns the exit code, then standard output and standard error. Stops after " + c.timeout().toSeconds()
                + " seconds.";
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return true;
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(config.onUnknown(), false, 0);
    }

    @Override
    public String target(Map<String, Object> args) {
        Object p = args.get("program");
        return p == null ? "program" : Limits.excerpt(String.valueOf(p), 60);
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws IOException, InterruptedException {
        String program = text(args, "program");
        Path executable = config.programs().get(program);
        if (executable == null) throw new ToolRefusal("program " + Limits.excerpt(program, 40) + " is not allowed (allowed: " + String.join(", ", config.programs().keySet()) + ")");
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments(args.get("args")));

        ProcessBuilder builder = new ProcessBuilder(command).directory(config.cwd().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", orElse(System.getenv("PATH"), "/usr/local/bin:/usr/bin:/bin"));
        env.put("LANG", orElse(System.getenv("LANG"), "C.UTF-8"));
        env.put("TZ", orElse(System.getenv("TZ"), "UTC"));
        for (String variable : config.envPass()) {
            String value = System.getenv(variable);
            if (value != null) env.put(variable, value);
        }

        Process process = builder.start();
        CompletableFuture<Limits.Capped> out = drain(process.getInputStream());
        CompletableFuture<Limits.Capped> err = drain(process.getErrorStream());
        boolean finished = process.waitFor(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new ToolRefusal(program + " ran longer than " + config.timeout().toSeconds() + "s and was stopped");
        }
        return "exit " + process.exitValue() + "\n" + section("stdout", out.join()) + section("stderr", err.join());
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<String> arguments(Object given) {
        if (given == null) return List.of();
        if (!(given instanceof List<?> list)) throw new ToolRefusal("args must be a list of strings");
        if (list.size() > MAX_ARGS) throw new ToolRefusal("too many arguments (the limit is " + MAX_ARGS + ")");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = String.valueOf(item);
            if (s.length() > MAX_ARG_LENGTH) throw new ToolRefusal("an argument is too long (the limit is " + MAX_ARG_LENGTH + " characters)");
            if (s.indexOf('\0') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) throw new ToolRefusal("arguments can't contain line breaks or NUL");
            out.add(s);
        }
        return out;
    }

    /** Reads the stream on its own thread: the first {@code maxOutput} bytes are kept, the rest discarded, so the process never blocks. */
    private CompletableFuture<Limits.Capped> drain(InputStream stream) {
        CompletableFuture<Limits.Capped> result = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (stream) {
                Limits.Capped capped = Limits.readCapped(stream, config.maxOutput());
                if (capped.truncated()) {
                    byte[] sink = new byte[8192];
                    while (stream.read(sink) != -1) { /* discard */ }
                }
                result.complete(capped);
            } catch (IOException e) {
                result.complete(new Limits.Capped(new byte[0], false));
            }
        }, "loom-shell-output");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    private static String section(String label, Limits.Capped c) {
        String text = c.text();
        if (text.isEmpty()) return label + ": (empty)\n";
        return label + ":\n" + text + (text.endsWith("\n") ? "" : "\n") + (c.truncated() ? Limits.markerUnknownTotal(c.bytes().length) + "\n" : "");
    }
}
