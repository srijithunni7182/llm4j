package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A {@code .env} file of keys for a developer's machine: {@code NAME=value} lines, nothing else. It is read, never written; the values are never
 * printed (only the names); a variable already in the environment wins; and a file git tracks is refused, because its keys are already in history.
 */
final class EnvFile {

    private static final Pattern LINE = Pattern.compile("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)");

    private EnvFile() {}

    /** The keys in the text (empty values are left out, so a copy of the example file does not hide a variable that is set). Throws with the line number, never the value. */
    static Map<String, String> parse(String text, String fileName) {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (String raw : text.split("\\R", -1)) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            var m = LINE.matcher(line);
            if (!m.matches()) throw new IllegalArgumentException(fileName + " line " + n + " is not NAME=value (the value is not shown)");
            String value = m.group(2).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) value = value.substring(0, hash).strip();
            }
            if (!value.isEmpty()) out.put(m.group(1), value);
        }
        return out;
    }

    /** The environment that also finds the file's keys; the shell's own variables win. Null (after saying why) when the file is refused or unreadable. */
    static WeaveEnv apply(WeaveEnv env, Path file, boolean explicit) {
        if (!Files.isRegularFile(file)) {
            env.err().println("Error: the env file " + file + " was not found.");
            return null;
        }
        String refusal = trackedByGit(file) ? file.getFileName() + " is tracked by git, so any key in it is already in the history. Remove it from git (git rm --cached "
                + file.getFileName() + "), add it to .gitignore, and rotate the keys that were in it." : null;
        if (refusal != null) {
            env.err().println("Error: " + refusal);
            return null;
        }
        Map<String, String> keys;
        try {
            keys = parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
        } catch (IOException | IllegalArgumentException e) {
            env.err().println("Error: could not read " + file + ": " + e.getMessage());
            return null;
        }
        for (String warning : warnings(file)) env.err().println("Warning: " + warning);
        if (!keys.isEmpty()) env.err().println("Using keys from " + file.getFileName() + ": " + String.join(", ", keys.keySet()));
        Function<String, String> shell = env.env();
        return env.withEnvLookup(name -> {
            String value = shell.apply(name);
            return value != null ? value : keys.get(name);
        });
    }

    /** Things worth saying that do not stop a run: not ignored by git, readable by other users. */
    static List<String> warnings(Path file) {
        List<String> out = new ArrayList<>();
        Path dir = file.toAbsolutePath().getParent();
        if (git(dir, "rev-parse", "--is-inside-work-tree") == 0 && git(dir, "check-ignore", "-q", "--", file.getFileName().toString()) == 1) {
            out.add(file.getFileName() + " is not in .gitignore, so git would offer it for commit. Add the line: " + file.getFileName());
        }
        try {
            Set<PosixFilePermission> p = Files.getPosixFilePermissions(file);
            if (p.contains(PosixFilePermission.GROUP_READ) || p.contains(PosixFilePermission.OTHERS_READ)
                    || p.contains(PosixFilePermission.GROUP_WRITE) || p.contains(PosixFilePermission.OTHERS_WRITE)) {
                out.add(file.getFileName() + " can be read by other users on this machine. Run: chmod 600 " + file.getFileName());
            }
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system: nothing to say
        }
        return out;
    }

    /** Whether git tracks the file (false when git is missing or this is not a repository). */
    static boolean trackedByGit(Path file) {
        return git(file.toAbsolutePath().getParent(), "ls-files", "--error-unmatch", "--", file.getFileName().toString()) == 0;
    }

    /** Runs git in {@code dir} and returns its exit code, or -1 when git cannot be run. */
    private static int git(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
