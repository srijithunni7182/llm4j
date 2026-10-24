package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.travel.ScriptHash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the script a run actually ran, so a candidate is compared against what happened and not
 * against what the file says today: a single named file or git revision, or (the default) the
 * version in git history whose fingerprint is the one the run recorded.
 */
public final class Baselines {

    private final Path candidate;
    private final Path file;
    private final String ref;
    private final Path temp;
    private final Map<String, Path> byHash = new HashMap<>();
    private boolean historyRead;

    private Baselines(Path candidate, Path file, String ref, Path temp) {
        this.candidate = candidate.toAbsolutePath().normalize();
        this.file = file;
        this.ref = ref;
        this.temp = temp;
    }

    /** What the baseline is, in words, for the report. */
    public String describe() {
        if (file != null) return "the file " + file.getFileName();
        if (ref != null) return "the script at git revision " + ref;
        return "the version each run recorded (found in git history)";
    }

    public static Baselines of(Path candidate, Path file, String gitRef, Path tempDir)
            throws IOException {
        Path temp = Files.createDirectories(tempDir);
        if (gitRef != null) {
            Path at = checkout(candidate, gitRef, temp.resolve("ref"));
            return new Baselines(candidate, at, gitRef, temp);
        }
        return new Baselines(
                candidate, file == null ? null : file.toAbsolutePath().normalize(), null, temp);
    }

    /** Whether the baseline is one fixed script rather than each run's own version. */
    public boolean fixed() {
        return file != null;
    }

    /** The script of the version with this fingerprint; empty when it cannot be recovered. */
    public Optional<Path> forHash(String hash) {
        if (file != null) return Optional.of(file);
        if (hash == null) return Optional.empty();
        if (ScriptHash.of(candidate).equals(hash)) return Optional.of(candidate);
        if (!historyRead) {
            historyRead = true;
            readHistory();
        }
        return Optional.ofNullable(byHash.get(hash));
    }

    /** Every version of the script's folder in the last commits that touched it. */
    private void readHistory() {
        try {
            Path root = repoRoot(candidate.getParent());
            if (root == null) return;
            String rel = root.relativize(candidate.getParent()).toString().replace('\\', '/');
            String path = rel.isEmpty() ? "." : rel;
            List<String> commits =
                    lines(root, "git", "log", "--format=%H", "-n", "200", "--", path);
            int n = 0;
            for (String c : commits) {
                Path dir = temp.resolve("h" + (n++));
                Path script;
                try {
                    script = checkout(candidate, c, dir);
                } catch (IOException e) {
                    continue; // a commit where the script was not there
                }
                byHash.putIfAbsent(ScriptHash.of(script), script);
            }
        } catch (IOException | InterruptedException e) {
            // no git here: only the working copy can be the baseline
        }
    }

    /** The script at {@code ref}, with the folder it lives in, extracted under {@code into}. */
    static Path checkout(Path script, String ref, Path into) throws IOException {
        try {
            Path root = repoRoot(script.getParent());
            if (root == null)
                throw new IOException(
                        script.getFileName()
                                + " is not in a git repository, so there is no revision "
                                + ref
                                + ".");
            String rel = root.relativize(script.getParent()).toString().replace('\\', '/');
            Files.createDirectories(into);
            Process p =
                    new ProcessBuilder(
                                    "git",
                                    "archive",
                                    "--format=tar",
                                    ref,
                                    rel.isEmpty() ? "." : rel)
                            .directory(root.toFile())
                            .start();
            Process tar =
                    new ProcessBuilder("tar", "-x", "-C", into.toString())
                            .redirectInput(ProcessBuilder.Redirect.PIPE)
                            .start();
            p.getInputStream().transferTo(tar.getOutputStream());
            tar.getOutputStream().close();
            if (p.waitFor() != 0 || tar.waitFor() != 0)
                throw new IOException(
                        "git has no revision \""
                                + ref
                                + "\" with "
                                + script.getFileName()
                                + " in it.");
            Path out = (rel.isEmpty() ? into : into.resolve(rel)).resolve(script.getFileName());
            if (!Files.isRegularFile(out))
                throw new IOException(script.getFileName() + " does not exist at " + ref + ".");
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static Path repoRoot(Path dir) throws IOException, InterruptedException {
        List<String> out = lines(dir, "git", "rev-parse", "--show-toplevel");
        return out.isEmpty() ? null : Path.of(out.get(0));
    }

    private static List<String> lines(Path dir, String... cmd)
            throws IOException, InterruptedException {
        Process p =
                new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
        List<String> out = new ArrayList<>();
        new String(p.getInputStream().readAllBytes())
                .lines()
                .filter(l -> !l.isBlank())
                .forEach(out::add);
        p.getErrorStream().readAllBytes();
        return p.waitFor() == 0 ? out : List.of();
    }
}
