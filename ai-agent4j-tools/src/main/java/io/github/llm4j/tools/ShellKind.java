package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectContext;

import io.github.llm4j.agent.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code tool Ops { use: shell  allow: "git, df, du" }}: lets an agent run a few named programs on this machine.
 * Programs are started directly with an argument list, never through a shell, so quoting and metacharacters
 * mean nothing. Because it can run local code, an agent must have it under {@code approve:} or the tool must
 * say {@code unattended: true}.
 */
public final class ShellKind extends GenericKind {

    /** Programs that run other programs, so allowing one allows anything. */
    private static final Set<String> WRAPPERS = Set.of("sh", "bash", "zsh", "dash", "fish", "ksh", "csh", "tcsh", "ash", "busybox",
            "cmd", "powershell", "pwsh", "node", "nodejs", "perl", "ruby", "php", "lua", "env", "xargs", "sudo", "su", "doas", "ssh",
            "find", "awk", "gawk", "sed", "nohup", "nice", "timeout", "watch", "setsid", "stdbuf", "strace", "ltrace", "make", "eval", "exec");
    private static final Pattern PYTHON = Pattern.compile("python[0-9.]*");

    private final String osName;

    public ShellKind() {
        this(System.getProperty("os.name", ""));
    }

    /** For tests: pretend to run on {@code osName}. */
    public ShellKind(String osName) {
        this.osName = osName;
    }

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public Set<String> required() {
        return Set.of("allow");
    }

    @Override
    public Set<String> optional() {
        return Set.of("cwd", "env_pass", "max_output", "unattended", "allow_interpreters", "on_unknown", "timeout");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        ShellTool.Config.parse(o, baseDir, osName);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        ShellTool.Config c = ShellTool.Config.parse(o, baseDir, osName);
        List<String> passed = new ArrayList<>();
        for (String variable : c.envPass()) {
            String v = System.getenv(variable);
            if (v != null) passed.add(v);
        }
        return new ShellTool(name, c, new Redactor(passed), context);
    }

    @Override
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        if (approved || "true".equals(options.get("unattended"))) return null;
        return "tool " + toolName + " runs programs on this machine: add it to " + agentName + "'s approve: list, "
                + "or set unattended: true on the tool if nobody should be asked";
    }

    // ── Shared with the tool ─────────────────────────────────────────────────────────────────

    /** Resolves each allowed name to an absolute program path; a name that isn't one program is an option error. */
    static Map<String, Path> resolveAllowed(List<String> names, boolean allowInterpreters) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (String name : names) {
            if (name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isWhitespace)) {
                throw new OptionException("allow: " + name + " must be a program name, not a path or a command line");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!allowInterpreters && (WRAPPERS.contains(lower) || PYTHON.matcher(lower).matches())) {
                throw new OptionException("allow: " + name + " can run any other program, which defeats the allow-list "
                        + "(allow_interpreters: true permits it)");
            }
            out.put(name, find(name));
        }
        return out;
    }

    private static Path find(String name) {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize();
            }
        }
        throw new OptionException("allow: " + name + " was not found on the PATH");
    }

    static Path resolveCwd(String cwd, Path baseDir) {
        Path dir;
        try {
            dir = SafePaths.inside(baseDir, cwd);
        } catch (IllegalArgumentException e) {
            throw new OptionException("cwd: " + e.getMessage());
        }
        if (!Files.isDirectory(dir)) throw new OptionException("cwd: " + cwd + " is not a directory");
        return dir;
    }
}
