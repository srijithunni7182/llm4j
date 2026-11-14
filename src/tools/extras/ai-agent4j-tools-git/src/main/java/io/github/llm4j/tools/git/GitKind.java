package io.github.llm4j.tools.git;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SandboxBackend;
import io.github.llm4j.tools.SandboxKind;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code tool Repo { use: git  workspace: Code  isolation: container  image: "alpine/git:2.47.2" }}:
 * status, diff, log, branch and commit on a workspace's repository, and a push only when the script
 * allows it, by running {@code git} through the sandbox (phase 35, D18), so the tool keeps the
 * sandbox's isolation, limits, journal and secrets by name.
 */
public final class GitKind extends GenericKind {

    /** The options of the git tool itself; every other option is the sandbox's. */
    static final Set<String> OWN = Set.of("push", "remote", "push_branches", "author_name", "author_email");

    static final String DEFAULT_AUTHOR = "llm4j agent";
    static final String DEFAULT_EMAIL = "agent@localhost";

    private final SandboxKind sandbox;

    /** Runs git with the standard sandbox backends. */
    public GitKind() {
        this(SandboxKind.forGit());
    }

    /** Runs git with the backends {@code backends} makes: a seam for tests (a {@code FakeSandbox}). */
    public GitKind(Function<SandboxKind.Settings, SandboxBackend> backends) {
        this(SandboxKind.forGit(backends));
    }

    private GitKind(SandboxKind sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "git";
    }

    @Override
    public Set<String> required() {
        return Set.of("workspace", "isolation");
    }

    @Override
    public Set<String> optional() {
        Set<String> s = new HashSet<>(Set.of("network", "hosts", "image", "runtime", "pull", "timeout", "max_output", "memory", "pids", "disk", "unattended"));
        s.addAll(OWN);
        return s;
    }

    @Override
    public Set<String> prefixes() {
        return sandbox.prefixes();
    }

    @Override
    public Set<String> resolvedPerCall() {
        return sandbox.resolvedPerCall();
    }

    @Override
    public Map<String, String> references() {
        return sandbox.references();
    }

    @Override
    public boolean buildsAtRunStart() {
        return true;
    }

    /** Diffs, logs and statuses show the repository's contents, which a model may have written. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("stdout", "stderr");
    }

    /** A push sends the repository out: the agent must have the tool approved, or it runs unattended. */
    @Override
    public String agentProblem(Map<String, String> options, String toolName, String agentName, boolean approved) {
        if (!"true".equals(options.get("push")) || approved || "true".equals(options.get("unattended"))) return null;
        return "tool " + toolName + " can push to a remote: add it to " + agentName
                + "'s approve: list, or set unattended: true on the tool if nobody should be asked";
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        ToolProfile box = sandbox.profile(sandboxOptions(options));
        boolean push = "true".equals(options.get("push"));
        String note = "git on the workspace " + options.getOrDefault("workspace", "")
                + " (isolation " + options.getOrDefault("isolation", "unset") + ")"
                + (push ? "; pushes to " + options.getOrDefault("remote", "origin") + ", branches " + options.get("push_branches") : "; no push");
        return new ToolProfile(box.untrusted(), true, push, TaskEffect.CHANGES, box.isolation(), true, true, note);
    }

    /** The options the sandbox underneath is built with: git as its only program. */
    static Map<String, String> sandboxOptions(Map<String, String> options) {
        Map<String, String> out = new LinkedHashMap<>();
        options.forEach((k, v) -> {
            if (!OWN.contains(k)) out.put(k, v);
        });
        out.put("allow", "git");
        out.put("unattended", "true"); // approval is the git tool's to ask, for a push
        return out;
    }

    /** The settings of one declaration, checked. */
    record Config(boolean push, String remote, List<java.util.regex.Pattern> pushBranches, String pushPattern, String author, String email, boolean token) {

        /** Whether a push may go to {@code branch}: {@code *} within a part of the name, {@code **} across. */
        boolean mayPush(String branch) {
            return push && pushBranches.stream().anyMatch(p -> p.matcher(branch).matches());
        }
    }

    static java.util.regex.Pattern glob(String pattern) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') re.append("[^/]*");
            else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return java.util.regex.Pattern.compile(re.toString());
    }

    static Config config(Options o) {
        boolean push = o.bool("push", false);
        if (push && !o.has("push_branches"))
            throw new OptionException("push_branches: is required with push: true, e.g. push_branches: \"agent/*\" (never a branch people work on)");
        if (!push && (o.has("push_branches") || o.has("remote")))
            throw new OptionException("push_branches: and remote: go with push: true");
        String remote = o.string("remote", "origin");
        try {
            GitCommands.ref(remote);
        } catch (RuntimeException e) {
            throw new OptionException("remote: " + e.getMessage());
        }
        if (push && "none".equals(o.string("network", "none")))
            throw new OptionException("network: a push needs the remote's host, e.g. network: allow  hosts: \"github.com\"");
        String pattern = o.get("push_branches");
        try {
            return new Config(
                    push,
                    remote,
                    pattern == null ? List.of() : o.list("push_branches").stream().map(GitKind::glob).toList(),
                    pattern,
                    GitCommands.author(o.string("author_name", DEFAULT_AUTHOR)),
                    GitCommands.email(o.string("author_email", DEFAULT_EMAIL)),
                    o.has("env." + GitCommands.TOKEN_VARIABLE));
        } catch (IllegalArgumentException e) {
            throw new OptionException(e.getMessage());
        }
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        config(o);
        String problem = sandbox.check(sandboxOptions(o.values()), baseDir);
        if (problem != null) throw new OptionException(problem);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) throws Exception {
        Config c = config(o);
        Tool runner = sandbox.create(name, sandboxOptions(o.values()), baseDir, new RunnerContext(context));
        Path repository = baseDir.toAbsolutePath().normalize().resolve(o.string("workspace.root", ".")).normalize();
        return new GitTool(name, c, runner, context, repository);
    }

    static List<String> actions() {
        return List.of("status", "diff", "log", "branches", "branch", "commit", "push");
    }
}
