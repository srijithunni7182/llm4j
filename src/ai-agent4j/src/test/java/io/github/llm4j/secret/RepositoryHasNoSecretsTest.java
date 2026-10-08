package io.github.llm4j.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No file in the repository may contain something shaped like a real credential. This would have caught the three test files that carried a
 * Google API key. Placeholders are fine when they say so (they contain "example", "test", "fake", "dummy", "your", repeated characters or the
 * like); a realistic-looking random string is not.
 */
class RepositoryHasNoSecretsTest {

    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    private static final List<Pattern> SHAPES = List.of(
            Pattern.compile("AIza[0-9A-Za-z_-]{35}"),                      // Google API key
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{30,}"),                   // Anthropic
            Pattern.compile("sk-(?:proj-)?[A-Za-z0-9_-]{40,}"),             // OpenAI-style
            Pattern.compile("AKIA[0-9A-Z]{16}"),                            // AWS access key id
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),                  // GitHub tokens
            Pattern.compile("github_pat_[A-Za-z0-9_]{50,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{20,}"),                // Slack
            Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"));

    private static final Set<String> TEXT = Set.of("java", "md", "xml", "json", "properties", "yaml", "yml", "sh", "txt", "loom", "loot", "kt",
            "js", "ts", "html", "csv", "toml", "gradle", "cfg", "conf", "env", "py", "sql", "mjs", "tsx", "jsx", "css");

    private static final Set<String> SKIP_DIRS = Set.of("target", ".git", "node_modules", "dist", ".idea", ".mvn", "out");

    /** True for strings that announce themselves as placeholders. */
    static boolean looksLikeAPlaceholder(String match) {
        String m = match.toLowerCase(Locale.ROOT);
        for (String word : new String[] {"example", "test", "fake", "dummy", "your", "placeholder", "redacted", "sample", "changeme", "xxxx", "0000", "1234"}) {
            if (m.contains(word)) return true;
        }
        // one or two distinct characters repeated: not random
        return match.chars().distinct().count() <= 4;
    }

    /** What looks like a real credential in {@code text}. */
    static List<String> findings(String text) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>(); // one finding per distinct string, whichever shapes it matches
        for (Pattern p : SHAPES) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (!looksLikeAPlaceholder(m.group())) found.add(m.group().substring(0, Math.min(8, m.group().length())) + "... (" + m.group().length() + " chars)");
            }
        }
        return new ArrayList<>(found);
    }

    @Test
    void theScannerCatchesRealisticKeysAndIgnoresPlaceholders() {
        // assembled at run time so this file does not itself contain a key-shaped string
        String googleShaped = "AIza" + "Sy" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q";
        String anthropicShaped = "sk-ant-" + "api03-" + "Zq9Xw8Vr7Ut6Ys5Pn4Om3Lk2Jh1Gf0Ed9Cb8Ba7";
        String awsShaped = "AKIA" + "QWERTYUIOPASDFGH";
        assertEquals(1, findings("key = \"" + googleShaped + "\"").size());
        assertEquals(1, findings("export X=" + anthropicShaped).size());
        assertEquals(1, findings(awsShaped).size());
        assertEquals(1, findings("-----BEGIN " + "PRIVATE KEY-----").size());
        assertTrue(findings("AIza" + "x".repeat(35)).isEmpty(), "a run of one character is a placeholder");
        assertTrue(findings("sk-ant-" + "api03-your-key-goes-here-0000000000000000").isEmpty());
        assertTrue(findings("apiKey(\"test-key\")").isEmpty());
        assertTrue(findings("ordinary text with no keys, just words and sk-learn").isEmpty());
    }

    @Test
    void noFileInTheRepositoryContainsACredentialShapedString() throws IOException {
        assertTrue(Files.isDirectory(ROOT.resolve("src/ai-agent4j")), "the test must run from a module directory of the repository: " + ROOT);
        List<String> problems = new ArrayList<>();
        try (Stream<Path> walk = candidateFiles()) {
            walk.filter(Files::isRegularFile).filter(p -> notSkipped(p)).forEach(p -> {
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!TEXT.contains(ext) && !name.equals(".env") && !name.startsWith(".env.")) return;
                try {
                    if (Files.size(p) > 5_000_000) return;
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    for (String f : findings(text)) problems.add(ROOT.relativize(p) + ": " + f);
                } catch (IOException | RuntimeException e) {
                    // unreadable or not UTF-8: not a text file we can scan
                }
            });
        }
        assertEquals(List.of(), problems, "a credential-shaped string is committed; remove it and rotate the credential");
    }

    /**
     * The files that are, or could become, part of the repository: tracked plus untracked-but-not-ignored. A git-ignored file such as a local
     * secrets.sh can never be committed, so it is not scanned; CI checks out committed files only, so it sees exactly what would ship. Without
     * git (a source tarball) every file under the root is scanned.
     */
    private static Stream<Path> candidateFiles() throws IOException {
        try {
            Process git = new ProcessBuilder("git", "-C", ROOT.toString(), "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .redirectErrorStream(false).start();
            byte[] out = git.getInputStream().readAllBytes();
            if (git.waitFor() == 0) {
                List<Path> files = new ArrayList<>();
                for (String rel : new String(out, StandardCharsets.UTF_8).split("\0")) if (!rel.isEmpty()) files.add(ROOT.resolve(rel));
                return files.stream();
            }
        } catch (IOException e) {
            // git not installed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Files.walk(ROOT);
    }

    private static boolean notSkipped(Path p) {
        for (Path part : ROOT.relativize(p)) if (SKIP_DIRS.contains(part.toString())) return false;
        return true;
    }
}
