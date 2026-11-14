package io.github.llm4j.tools.git;

import io.github.llm4j.tools.ToolRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The argument lists the git tool runs: a fixed vocabulary, never a command line from the model.
 * Every list starts with settings that keep a repository's own configuration from running
 * anything (D18: {@code .git/hooks} never run): no hooks, no fsmonitor, no pager, no external diff
 * or text conversion, and only the https transport.
 */
final class GitCommands {

    static final int MAX_LOG = 50;
    static final int MAX_MESSAGE = 2_000;
    static final String TOKEN_VARIABLE = "GIT_TOKEN";
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+");

    private GitCommands() {}

    /** What every git call starts with. */
    static List<String> hardened(boolean withToken) {
        List<String> a = new ArrayList<>();
        for (String setting :
                List.of(
                        "core.hooksPath=/dev/null",
                        "core.fsmonitor=false",
                        "core.pager=cat",
                        "core.editor=false",
                        "core.askPass=",
                        "diff.external=",
                        "protocol.allow=never",
                        "protocol.https.allow=always",
                        "credential.helper=")) {
            a.add("-c");
            a.add(setting);
        }
        if (withToken) {
            // the token is read by the helper from the environment, never put on a command line
            a.add("-c");
            a.add("credential.helper=!f() { echo username=x-access-token; echo \"password=$" + TOKEN_VARIABLE + "\"; }; f");
        }
        a.add("--no-pager");
        return a;
    }

    static List<String> status() {
        return List.of("status", "--porcelain=v1", "--branch");
    }

    static List<String> diff(boolean staged, String path) {
        List<String> a = new ArrayList<>(List.of("diff", "--no-ext-diff", "--no-textconv", "--no-color"));
        if (staged) a.add("--cached");
        if (path != null) {
            a.add("--");
            a.add(path(path));
        }
        return a;
    }

    static List<String> log(int max) {
        if (max < 1 || max > MAX_LOG) throw new ToolRefusal("max must be between 1 and " + MAX_LOG);
        return List.of(
                "log", "--no-decorate", "--no-color", "--date=iso-strict", "--format=%H%x09%an%x09%ad%x09%s", "-n",
                String.valueOf(max));
    }

    static List<String> branches() {
        return List.of("branch", "--list", "--no-color", "--format=%(refname:short)");
    }

    static List<String> newBranch(String name) {
        return List.of("switch", "-c", ref(name));
    }

    static List<String> add(List<String> paths) {
        List<String> a = new ArrayList<>(List.of("add", "--"));
        if (paths.isEmpty()) a.add(".");
        for (String p : paths) a.add(path(p));
        return a;
    }

    static List<String> commit(String message, String author, String email) {
        if (message == null || message.isBlank()) throw new ToolRefusal("message is required");
        if (message.length() > MAX_MESSAGE) throw new ToolRefusal("message is longer than " + MAX_MESSAGE + " characters");
        return List.of("-c", "user.name=" + author, "-c", "user.email=" + email, "commit", "--no-verify", "--no-gpg-sign", "-m", message);
    }

    static List<String> push(String remote, String branch) {
        return List.of("push", "--no-verify", ref(remote), "HEAD:refs/heads/" + ref(branch));
    }

    /** A branch or remote name: no option, no {@code ..}, no lock file, no path climbing. */
    static String ref(String name) {
        if (name == null || !REF.matcher(name).matches() || name.contains("..") || name.contains("//")
                || name.endsWith("/") || name.endsWith(".lock") || name.endsWith("."))
            throw new ToolRefusal("\"" + name + "\" is not a branch name this tool uses (letters, digits, . _ / -; not starting with -)");
        return name;
    }

    /** A path in the workspace: relative, never an option, never climbing out. */
    static String path(String path) {
        if (path.isBlank() || path.startsWith("-") || path.startsWith("/") || path.contains("\\")
                || List.of(path.split("/")).contains("..") || path.startsWith(":"))
            throw new ToolRefusal("path " + path + " must be relative, inside the workspace and not start with - or :");
        return path;
    }

    static String author(String name) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("author_name: letters, digits, spaces and . _ -");
        return name;
    }

    static String email(String email) {
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("author_email: is not an address");
        return email;
    }
}
