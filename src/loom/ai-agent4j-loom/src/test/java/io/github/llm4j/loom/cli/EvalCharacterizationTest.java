package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D17 rule 5 and G67: what {@code weave eval --mock} prints, and what it writes with
 * {@code --json}, for every evaluated script in the repository (the starters and the templates),
 * pinned before eval4j changes. A difference fails with the scripts whose output changed. {@code
 * -Dcharacterization.record=true} writes the golden file.
 */
class EvalCharacterizationTest {

    static final Path STARTERS = Path.of("../starters");
    static final Path TEMPLATES = Path.of("src/main/resources/templates");
    static final Path GOLDEN = Path.of("src/test/resources/characterization/eval-outputs.tsv");

    @TempDir Path work;

    static List<Path> evaluated() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : List.of(STARTERS, TEMPLATES))
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(d -> Files.isRegularFile(d.resolve("main.loom")) && Files.isDirectory(d.resolve("eval/golden")))
                        .sorted()
                        .forEach(out::add);
            }
        return out;
    }

    /** Copies a project, runs it, and returns what it printed and the JSON, with what varies taken out. */
    String evaluate(Path project) throws Exception {
        Path copy = work.resolve(project.getFileName().toString());
        try (Stream<Path> files = Files.walk(project)) {
            for (Path f : files.toList()) {
                Path to = copy.resolve(project.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(to);
                else Files.copy(f, to);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        WeaveEnv env = new WeaveEnv(
                model -> {
                    throw new IllegalStateException("a mock run asks no real model");
                },
                m -> "yes",
                print,
                print,
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                k -> null);
        Path json = copy.resolve("eval-out.json");
        EvalCommand c = new EvalCommand();
        new picocli.CommandLine(c).parseArgs(copy.resolve("main.loom").toString(), "--mock", "--json", json.toString());
        int code = EvalCommand.eval(c, env);
        String printed = out.toString(StandardCharsets.UTF_8);
        String written = Files.exists(json) ? Files.readString(json) : "";
        return normalize("exit " + code + "\n" + printed + "\n" + written, copy);
    }

    static String normalize(String text, Path copy) {
        return text.replace(copy.toAbsolutePath().toString(), "<project>")
                .replace(copy.toString(), "<project>")
                .replaceAll("eval-\\d{8}T\\d{6}Z-[0-9a-f]{4}", "eval-<run>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?", "<time>")
                .replaceAll("\\b\\d+(\\.\\d+)? ?(ms|s)\\b", "<duration>")
                .replaceAll("\"(durationMs|millis|duration_ms|elapsedMs)\"\\s*:\\s*\\d+", "\"$1\":0")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
                .replaceAll("\\b[0-9a-f]{16,64}\\b", "<hash>");
    }

    /** A JSON array of strings on one line, with its items in sorted order. */
    static String sortedItems(String line) {
        int open = line.indexOf("[ \"");
        int close = line.lastIndexOf("\" ]");
        if (open < 0 || close < open) return line;
        List<String> items = new ArrayList<>(List.of(line.substring(open + 3, close).split("\", \"")));
        java.util.Collections.sort(items);
        return line.substring(0, open + 3) + String.join("\", \"", items) + line.substring(close);
    }

    /**
     * The lines' digest, in sorted order: a few lines (the fingerprint) come out in an order that
     * depends on the run's id, so the content is pinned and the order is not.
     */
    static String digest(String s) throws Exception {
        s = String.join("\n", s.lines().map(EvalCharacterizationTest::sortedItems).sorted().toList());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    @Test
    void weaveEvalSaysTheSameForEveryEvaluatedScript() throws Exception {
        Map<String, String> now = new TreeMap<>();
        for (Path p : evaluated()) now.put(p.toString().replace('\\', '/'), digest(evaluate(p)));
        assertThat(now).as("evaluated scripts found").hasSizeGreaterThan(10);
        if (Boolean.getBoolean("characterization.record")) {
            StringBuilder b = new StringBuilder();
            now.forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
            Files.writeString(GOLDEN, b.toString());
            return;
        }
        Map<String, String> pinned = new TreeMap<>();
        for (String line : Files.readAllLines(GOLDEN)) {
            String[] kv = line.split("\t");
            if (kv.length == 2) pinned.put(kv[0], kv[1]);
        }
        assertThat(now).isEqualTo(pinned);
    }

    @Test
    void theOutputDoesNotChangeFromOneRunToTheNext() throws Exception {
        Path first = evaluated().get(0);
        String a = evaluate(first);
        Files.walk(work).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        Files.createDirectories(work);
        assertThat(evaluate(first)).isEqualTo(a);
    }
}
