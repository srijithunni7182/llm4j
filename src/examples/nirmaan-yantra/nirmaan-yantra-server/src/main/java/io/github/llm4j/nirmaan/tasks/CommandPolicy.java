package io.github.llm4j.nirmaan.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a build, test or run command written by an agent may be executed. The commands come from a model, so the
 * rule is an allow list, never a deny list:
 * <ul>
 *   <li>{@link Decision#APPROVED}: every part (split on {@code &&}) starts with a known build or test tool, or runs a script inside the
 *       project, and uses no shell syntax and no path outside the project;</li>
 *   <li>{@link Decision#BLOCKED}: a part starts with something destructive or privileged ({@code rm}, {@code sudo}, {@code dd}, ...);</li>
 *   <li>{@link Decision#NEEDS_REVIEW}: anything else. It is not run until a person says yes.</li>
 * </ul>
 * The command is later started directly, with no shell, so {@code ;}, {@code |}, {@code $( )} and wildcards mean nothing to it either way.
 */
public final class CommandPolicy {

    public enum Decision { APPROVED, NEEDS_REVIEW, BLOCKED }

    /** What a classification found: the verdict, why, and the command split into argument lists (empty unless runnable). */
    public record Result(Decision decision, String reason, List<List<String>> parts) {}

    private static final Set<String> BUILD_TOOLS = Set.of("mvn", "./mvnw", "gradle", "./gradlew", "npm", "npx", "yarn", "pnpm", "node", "python", "python3", "pip", "pip3",
            "pytest", "go", "cargo", "make", "java", "javac", "dotnet", "bundle", "rake", "composer", "phpunit", "deno", "bun");
    private static final Set<String> SCRIPT_RUNNERS = Set.of("sh", "bash");
    private static final Set<String> FORBIDDEN = Set.of("rm", "rmdir", "dd", "mkfs", "shred", "truncate", "sudo", "su", "doas", "kill", "killall", "pkill", "chmod", "chown",
            "mv", "shutdown", "reboot", "halt", "mount", "umount", "crontab", "systemctl", "eval", "exec", "nc", "ncat", "ssh", "scp");
    private static final String SYNTAX = ";|&`$<>(){}*?~\\\n\r";

    private CommandPolicy() {}

    public static Result classify(String command) {
        if (command == null || command.isBlank()) return new Result(Decision.NEEDS_REVIEW, "the command is empty", List.of());
        List<List<String>> parts = new ArrayList<>();
        for (String raw : command.strip().split("&&", -1)) {
            String part = raw.strip();
            if (part.isEmpty()) return new Result(Decision.NEEDS_REVIEW, "an empty step around &&", List.of());
            List<String> words = split(part);
            if (words == null) return new Result(Decision.NEEDS_REVIEW, "unbalanced quotes in: " + part, List.of());
            parts.add(words);
        }
        boolean unsure = false;
        String why = "";
        for (List<String> words : parts) {
            String first = words.get(0).toLowerCase(Locale.ROOT);
            String program = first.contains("/") && !first.startsWith("./") ? first.substring(first.lastIndexOf('/') + 1) : first;
            if (FORBIDDEN.contains(program)) return new Result(Decision.BLOCKED, "\"" + words.get(0) + "\" is not allowed in a build", List.of());
            String bad = syntaxIn(words);
            if (bad != null) { unsure = true; why = bad; continue; }
            boolean known = BUILD_TOOLS.contains(first)
                    || (SCRIPT_RUNNERS.contains(first) && words.size() >= 2 && insideProject(words.get(1)))
                    || (first.startsWith("./") && insideProject(first));
            if (!known) { unsure = true; why = "\"" + words.get(0) + "\" is not a known build tool"; }
        }
        return unsure ? new Result(Decision.NEEDS_REVIEW, why, List.of()) : new Result(Decision.APPROVED, "", List.copyOf(parts));
    }

    /** The argument lists of a command a person has agreed to run (the same splitting, no verdict). */
    public static List<List<String>> parts(String command) {
        List<List<String>> parts = new ArrayList<>();
        for (String raw : command.strip().split("&&", -1)) {
            List<String> words = split(raw.strip());
            if (words == null || words.isEmpty()) return List.of();
            parts.add(words);
        }
        return parts;
    }

    private static String syntaxIn(List<String> words) {
        for (String word : words) {
            for (char c : word.toCharArray()) {
                if (SYNTAX.indexOf(c) >= 0) return "\"" + word + "\" uses shell syntax (" + c + ")";
            }
            if (word.startsWith("/") ) return "\"" + word + "\" is a path outside the project";
            if (word.equals("..") || word.startsWith("../") || word.contains("/../") || word.endsWith("/..")) return "\"" + word + "\" climbs out of the project";
        }
        return null;
    }

    /** A script path inside the project: not an option such as {@code -c} (which would run text as a program), not absolute, not climbing out. */
    private static boolean insideProject(String path) {
        return !path.startsWith("-") && !path.startsWith("/") && !path.startsWith("~") && !path.contains("..");
    }

    /** Splits on spaces, honouring single and double quotes. Null when a quote is left open. */
    static List<String> split(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (char c : text.toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0; else current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started || current.length() > 0) { words.add(current.toString()); current.setLength(0); started = false; }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (quote != 0) return null;
        if (started || current.length() > 0) words.add(current.toString());
        return words;
    }
}
