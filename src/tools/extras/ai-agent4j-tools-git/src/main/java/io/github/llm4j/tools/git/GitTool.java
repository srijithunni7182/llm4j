package io.github.llm4j.tools.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One declared git tool: an action in, git's exit code and output out, through the sandbox. */
final class GitTool extends GenericTool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CHANGES = Set.of("branch", "commit", "push");

    private final GitKind.Config config;
    private final Tool runner;

    GitTool(String name, GitKind.Config config, Tool runner, EffectContext context) {
        super(name, "git", description(config), Redactor.NONE, context);
        this.config = config;
        this.runner = runner;
    }

    private static String description(GitKind.Config c) {
        return "Works with the workspace's git repository. action: status, diff (staged, path), log (max, at most "
                + GitCommands.MAX_LOG
                + "), branches, branch (name: creates it and switches to it), commit (message, paths: what to add, all"
                + " changes by default)"
                + (c.push() ? ", push (branch, one of " + c.pushPattern() + ", to " + c.remote() + "; never forced)" : "")
                + ". Returns exit, stdout and stderr.";
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return CHANGES.contains(action(args));
    }

    /** A push after an unknown outcome is not repeated blindly; a commit is not either. */
    @Override
    public EffectPolicy policy() {
        return EffectPolicy.DEFAULT;
    }

    @Override
    public String target(Map<String, Object> args) {
        Object branch = args.get("branch");
        return "git " + action(args) + (branch == null ? "" : " " + branch);
    }

    private static String action(Map<String, Object> args) {
        Object a = args.get("action");
        return a == null ? "status" : String.valueOf(a).strip().toLowerCase(Locale.ROOT);
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws Exception {
        String action = action(args);
        Map<String, Object> out =
                switch (action) {
                    case "status" -> git(GitCommands.status());
                    case "diff" -> git(GitCommands.diff(Boolean.parseBoolean(String.valueOf(args.get("staged"))), optionalText(args, "path")));
                    case "log" -> git(GitCommands.log(number(args.get("max"), 10)));
                    case "branches" -> git(GitCommands.branches());
                    case "branch" -> changed(git(GitCommands.newBranch(text(args, "name"))));
                    case "commit" -> commit(args);
                    case "push" -> push(text(args, "branch"));
                    default -> throw new ToolRefusal("action must be one of " + String.join(", ", GitKind.actions()) + ", not " + action);
                };
        out.put("action", action);
        return JSON.writeValueAsString(out);
    }

    private Map<String, Object> commit(Map<String, Object> args) throws Exception {
        List<String> paths = new ArrayList<>();
        if (args.get("paths") instanceof List<?> list) for (Object p : list) paths.add(String.valueOf(p));
        else if (args.get("paths") != null) throw new ToolRefusal("paths must be a list of paths");
        List<String> commit = GitCommands.commit(text(args, "message"), config.author(), config.email());
        changed(git(GitCommands.add(paths)));
        return changed(git(commit));
    }

    private Map<String, Object> push(String branch) throws Exception {
        if (!config.push()) throw new ToolRefusal("this git tool does not push (the script did not allow it: push: true)");
        GitCommands.ref(branch);
        if (!config.mayPush(branch))
            throw new ToolRefusal("a push to " + branch + " is not allowed (push_branches: " + config.pushPattern() + ")");
        return changed(git(GitCommands.push(config.remote(), branch)));
    }

    /** An action that changes something fails loudly when git does, so it is not recorded as done. */
    private static Map<String, Object> changed(Map<String, Object> result) {
        if (((Number) result.get("exit")).intValue() != 0)
            throw new ToolRefusal("git failed (exit " + result.get("exit") + "): " + result.get("stderr"));
        return result;
    }

    private Map<String, Object> git(List<String> command) throws Exception {
        List<String> all = new ArrayList<>(GitCommands.hardened(config.token()));
        all.addAll(command);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("program", "git");
        call.put("args", all);
        String reply = runner.execute(call);
        if (reply.startsWith("Error:") || reply.startsWith("(")) throw new ToolRefusal(reply.replaceFirst("^Error:\\s*", ""));
        JsonNode r = JSON.readTree(reply);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exit", r.path("exit").asInt());
        out.put("isolation", r.path("isolation").asText());
        out.put("stdout", r.path("stdout").asText());
        out.put("stderr", r.path("stderr").asText());
        out.put("truncated", r.path("truncated").asBoolean());
        return out;
    }

    private static int number(Object given, int fallback) {
        if (given == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(given).strip());
        } catch (NumberFormatException e) {
            throw new ToolRefusal("max must be a whole number");
        }
    }
}
