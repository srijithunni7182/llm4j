package io.github.llm4j.tools.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.Fuzz;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.agent.tool.EffectJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileToolTest {

    @TempDir
    Path dir;
    RecordingEffects ctx;
    Declared declared;
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        ctx = new RecordingEffects();
        declared = new Declared(Map.of(), dir);
        root = dir.resolve("notes");
        Files.createDirectories(root);
        Files.writeString(dir.resolve("outside.md"), "OUTSIDE-SECRET");
        Files.writeString(root.resolve(".env"), "HIDDEN-SECRET");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/x.md"), "HIDDEN-SECRET");
        Files.writeString(root.resolve("a.md"), "line1\nline2\nline3\nline4\n");
        Files.writeString(root.resolve("data.bin"), "not allowed by name");
    }

    Tool tool(String options) throws Exception {
        return declared.create("tool Notes { use: file  root: \"notes/\"  " + options + " }", ctx);
    }

    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    // ── V7.1 writes ──────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V7.1")
    void writeCreatesTheFileAndRefusesToReplaceItUnlessAllowed() throws Exception {
        Tool t = tool("mode: write");
        assertThat(run(t, Map.of("action", "write", "path", "new.md", "content", "hello"))).startsWith("Wrote 5 bytes");
        assertThat(Files.readString(root.resolve("new.md"))).isEqualTo("hello");

        assertThat(run(t, Map.of("action", "write", "path", "new.md", "content", "again"))).startsWith("Error:").contains("already exists");
        assertThat(Files.readString(root.resolve("new.md"))).isEqualTo("hello");

        Tool replacing = tool("mode: write  overwrite: true");
        assertThat(run(replacing, Map.of("action", "write", "path", "new.md", "content", "replaced"))).startsWith("Wrote");
        assertThat(Files.readString(root.resolve("new.md"))).isEqualTo("replaced");
    }

    @Test
    @Tag("V7.1")
    void writeIsAtomicSoAReaderNeverSeesAPartialFile() throws Exception {
        Tool t = tool("mode: write  overwrite: true");
        String big = "x".repeat(900_000);
        Files.writeString(root.resolve("big.md"), "old");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var writes = pool.submit(() -> {
                for (int i = 0; i < 30; i++) run(t, Map.of("action", "write", "path", "big.md", "content", big + i));
            });
            while (!writes.isDone()) {
                String seen = Files.readString(root.resolve("big.md"));
                assertThat(seen.equals("old") || seen.length() >= 900_000).as("saw a partial file of " + seen.length()).isTrue();
            }
            writes.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Tag("V7.1")
    void appendCreatesTheFileAndKeepsLinesApart() throws Exception {
        Tool t = tool("mode: write");
        run(t, Map.of("action", "append", "path", "log.md", "content", "first"));
        run(t, Map.of("action", "append", "path", "log.md", "content", "second\n"));
        Files.writeString(root.resolve("nonl.md"), "no newline at end");
        run(t, Map.of("action", "append", "path", "nonl.md", "content", "next"));

        assertThat(Files.readString(root.resolve("log.md"))).isEqualTo("first\nsecond\n");
        assertThat(Files.readString(root.resolve("nonl.md"))).isEqualTo("no newline at end\nnext\n");
    }

    @Test
    @Tag("V7.1")
    void writeCreatesMissingDirectoriesInsideTheRoot() throws Exception {
        run(tool("mode: write"), Map.of("action", "write", "path", "sub/dir/n.md", "content", "x"));
        assertThat(root.resolve("sub/dir/n.md")).exists();
    }

    // ── V7.2 reads ───────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V7.2")
    void readReturnsContentAndCanSelectAWindowOfLines() throws Exception {
        Tool t = tool("");
        assertThat(run(t, Map.of("action", "read", "path", "a.md"))).isEqualTo("line1\nline2\nline3\nline4\n");
        assertThat(run(t, Map.of("action", "read", "path", "a.md", "from_line", 2, "lines", 2))).isEqualTo("line2\nline3");
        assertThat(run(t, Map.of("action", "read", "path", "a.md", "from_line", "9"))).isEmpty();
    }

    @Test
    @Tag("V7.2")
    void aLargeFileIsCutAtMaxBytesWithAMarker() throws Exception {
        Files.writeString(root.resolve("big.txt"), "y".repeat(5000));
        String out = run(tool("max_bytes: 1k"), Map.of("action", "read", "path", "big.txt"));
        assertThat(out).startsWith("y".repeat(1024)).contains("[cut: 1024 of 5000 bytes shown]");
    }

    @Test
    @Tag("V7.2")
    void aBinaryFileIsRefused() throws Exception {
        Files.write(root.resolve("img.log"), new byte[] {'a', 0, 'b'});
        assertThat(run(tool(""), Map.of("action", "read", "path", "img.log"))).startsWith("Error:").contains("not a text file");
    }

    @Test
    @Tag("V7.2")
    void existsAndMissingFiles() throws Exception {
        Tool t = tool("");
        assertThat(run(t, Map.of("action", "exists", "path", "a.md"))).isEqualTo("true");
        assertThat(run(t, Map.of("action", "exists", "path", "nope.md"))).isEqualTo("false");
        assertThat(run(t, Map.of("action", "read", "path", "nope.md"))).startsWith("Error:").contains("doesn't exist");
    }

    // ── V7.3 confinement ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"../outside.md", "../../etc/passwd", "/etc/passwd", "sub/../../outside.md", ".env", ".hidden/x.md",
            "sub/.hidden", "data.bin"})
    @Tag("V7.3")
    @Tag("H4")
    void escapesHiddenFilesAndDisallowedNamesAreRefused(String path) throws Exception {
        String out = run(tool("mode: readwrite  overwrite: true"), Map.of("action", "read", "path", path));
        assertThat(out).startsWith("Error:").doesNotContain("SECRET");
        String written = run(tool("mode: readwrite  overwrite: true"), Map.of("action", "write", "path", path, "content", "PWNED"));
        assertThat(written).startsWith("Error:");
        assertThat(Files.readString(dir.resolve("outside.md"))).isEqualTo("OUTSIDE-SECRET");
        assertThat(Files.readString(root.resolve(".env"))).isEqualTo("HIDDEN-SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb.md", "a\r\nBcc: evil@evil.example\r\nX-Injected: yes.md", "tab\there.md", "bell\u0007.md", "del\u007f.md",
            "next\u0085line.md", "ls\u2028sep.md", "ps\u2029sep.md", "sub/a\nb.md"})
    @Tag("V7.3")
    @Tag("H4")
    void aNameWithAControlOrLineBreakCharacterIsRefusedEverywhere(String path) throws Exception {
        Tool t = tool("mode: readwrite  overwrite: true  allow: \"*\"");
        for (String action : List.of("write", "append", "read", "exists")) {
            assertThat(run(t, Map.of("action", action, "path", path, "content", "x"))).as(action).startsWith("Error:").contains("control or line-break");
        }
        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.chars().anyMatch(c -> c < 0x20 || c == 0x7f));
        }
    }

    @Test
    @Tag("V7.3")
    @Tag("H4")
    void aSymlinkLeadingOutIsRefused() throws Exception {
        try {
            Files.createSymbolicLink(root.resolve("link.md"), dir.resolve("outside.md"));
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "can't create symlinks here");
        }
        assertThat(run(tool(""), Map.of("action", "read", "path", "link.md"))).startsWith("Error:").doesNotContain("OUTSIDE-SECRET");
        assertThat(run(tool("mode: readwrite  overwrite: true"), Map.of("action", "write", "path", "link.md", "content", "PWNED"))).startsWith("Error:");
        assertThat(Files.readString(dir.resolve("outside.md"))).isEqualTo("OUTSIDE-SECRET");
    }

    @Test
    @Tag("V7.3")
    @Tag("H4")
    void theRunsOwnFilesAreOutOfReachEvenWhenTheyAreNotHidden() throws Exception {
        Path journalDir = root.resolve("journal");
        Files.createDirectories(journalDir);
        Files.writeString(journalDir.resolve("run.json"), "JOURNAL-SECRET");
        ctx.reserved.add(journalDir.toAbsolutePath().normalize());

        Tool t = tool("allow: \"*.json, *.md\"  mode: readwrite  overwrite: true");

        assertThat(run(t, Map.of("action", "read", "path", "journal/run.json"))).startsWith("Error:").contains("belongs to the run");
        assertThat(run(t, Map.of("action", "write", "path", "journal/run.json", "content", "x"))).startsWith("Error:");
        assertThat(run(t, Map.of("action", "list"))).doesNotContain("journal");
        assertThat(Files.readString(journalDir.resolve("run.json"))).isEqualTo("JOURNAL-SECRET");
    }

    @Test
    @Tag("V7.3")
    void listIsSortedOmitsHiddenAndDisallowedNamesAndMarksDirectories() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("b.md"), "x");
        assertThat(run(tool(""), Map.of("action", "list"))).isEqualTo("a.md\nb.md\nsub/");
        assertThat(run(tool(""), Map.of("action", "list", "pattern", "a*"))).isEqualTo("a.md");
        assertThat(run(tool(""), Map.of("action", "list", "path", "sub"))).isEqualTo("(empty)");
    }

    @Test
    @Tag("V7.3")
    void listIsCappedAt500() throws Exception {
        for (int i = 0; i < 520; i++) Files.writeString(root.resolve(String.format("f%03d.md", i)), "x");
        String out = run(tool(""), Map.of("action", "list"));
        assertThat(out.lines().count()).as("500 entries and the note").isEqualTo(501L);
        assertThat(out).endsWith("… [21 more not shown]"); // 520 new files and a.md
    }

    // ── V7.4 modes, V7.5 root ────────────────────────────────────────────────────────────────

    @Test
    @Tag("V7.4")
    void modesAreEnforcedAndTheDescriptionListsOnlyWhatIsAllowed() throws Exception {
        Tool reader = tool("mode: read");
        assertThat(run(reader, Map.of("action", "write", "path", "n.md", "content", "x"))).startsWith("Error:").contains("read only");
        assertThat(reader.getDescription()).contains("read (path").doesNotContain("write (path");

        Tool writer = tool("mode: write");
        assertThat(run(writer, Map.of("action", "read", "path", "a.md"))).startsWith("Error:").contains("write only");
        assertThat(run(writer, Map.of("action", "list"))).startsWith("Error:");
        assertThat(writer.getDescription()).contains("write (path").doesNotContain("read (path");
        assertThat(run(writer, Map.of("action", "exists", "path", "a.md"))).isEqualTo("true");
    }

    @Test
    @Tag("V7.5")
    void aRootOutsideTheScriptDirectoryOrAFileIsALoadError() throws Exception {
        assertThat(declared.problems("tool N { use: file  root: \"../elsewhere\" }")).anyMatch(p -> p.contains("root:") && p.contains("outside"));
        assertThat(declared.problems("tool N { use: file  root: \"/etc\" }")).anyMatch(p -> p.contains("root:"));
        assertThat(declared.problems("tool N { use: file  root: \"outside.md\" }")).anyMatch(p -> p.contains("is a file"));
        assertThat(declared.problems("tool N { use: file  root: \"notes/\"  mode: delete }")).anyMatch(p -> p.contains("mode"));
        assertThat(declared.problems("tool N { use: file  allow: \" , \" }")).anyMatch(p -> p.contains("allow"));
        assertThat(declared.problems("tool N { use: file }")).isEmpty();
    }

    @Test
    @Tag("V1.12")
    void thereIsNoWayToDeleteAnything() throws Exception {
        Tool t = tool("mode: readwrite  overwrite: true");
        assertThat(run(t, Map.of("action", "delete", "path", "a.md"))).startsWith("Error:");
        assertThat(run(t, Map.of("action", "remove", "path", "a.md"))).startsWith("Error:");
        assertThat(t.getDescription()).doesNotContain("delete");
        assertThat(root.resolve("a.md")).exists();
    }

    @Test
    @Tag("V1.6")
    void badArgumentsAreErrorsNotExceptions() throws Exception {
        Tool t = tool("mode: readwrite");
        assertThat(run(t, Map.of())).startsWith("Error:");
        assertThat(run(t, Map.of("action", "read"))).contains("path is required");
        assertThat(run(t, Map.of("action", "write", "path", "x.md"))).contains("content is required");
        assertThat(run(t, Map.of("action", "write", "path", "x.md", "content", "a".repeat(1024 * 1024 + 1)))).contains("too large");
        assertThat(run(t, Map.of("action", "read", "path", "a.md", "from_line", "abc"))).contains("whole number");
        assertThat(run(t, Map.of("action", "read", "path", "a\u0000b.md"))).startsWith("Error:");
    }

    // ── V7.6 / C2 concurrency ────────────────────────────────────────────────────────────────

    @Test
    @Tag("V7.6")
    @Tag("C2")
    void parallelAppendsProduceWholeLinesNeverInterleaved() throws Exception {
        Tool t = tool("mode: write");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int thread = 0; thread < 8; thread++) {
            int id = thread;
            pool.submit(() -> {
                for (int i = 0; i < 200; i++) run(t, Map.of("action", "append", "path", "shared.md", "content", "t" + id + "-line-" + i + "-" + "z".repeat(50)));
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> lines = Files.readAllLines(root.resolve("shared.md"));
        assertThat(lines).hasSize(1600).allMatch(l -> l.matches("t\\d-line-\\d+-z{50}"));
    }

    // ── V7.7 effects ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V7.7")
    void writesAreJournaledAndReadsAreNot() throws Exception {
        Tool t = tool("mode: readwrite");
        run(t, Map.of("action", "append", "path", "log.md", "content", "entry"));
        run(t, Map.of("action", "read", "path", "log.md"));
        run(t, Map.of("action", "list"));
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_done");

        // A resumed run replays the append instead of appending twice.
        String resumed = run(tool("mode: readwrite"), Map.of("action", "append", "path", "log.md", "content", "entry"));
        assertThat(resumed).contains("already done");
        assertThat(Files.readString(root.resolve("log.md"))).isEqualTo("entry\n");
    }

    // ── F2 generated paths ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("F2")
    void generatedPathsNeverReachOutsideTheRootOrHiddenFiles() throws Exception {
        Tool t = tool("mode: readwrite  overwrite: true  allow: \"*\"");
        String alphabet = "a.b/\\@:%\u0000\n .~..//..․．／";
        Path outsideBefore = dir.resolve("outside.md");
        Fuzz.run("file-paths", random -> {
            String path = randomPath(random, alphabet);
            String read = run(t, Map.of("action", "read", "path", path));
            assertThat(read).doesNotContain("OUTSIDE-SECRET").doesNotContain("HIDDEN-SECRET");
            run(t, Map.of("action", "write", "path", path, "content", "FUZZ-WRITE"));
            run(t, Map.of("action", "append", "path", path, "content", "FUZZ-APPEND"));
        });
        try (Stream<Path> created = Files.walk(root)) {
            assertThat(created.map(p -> p.getFileName().toString())).as("no generated name carries a control character")
                    .noneMatch(n -> n.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029));
        }

        assertThat(Files.readString(outsideBefore)).isEqualTo("OUTSIDE-SECRET");
        assertThat(Files.readString(root.resolve(".env"))).isEqualTo("HIDDEN-SECRET");
        assertThat(Files.readString(root.resolve(".hidden/x.md"))).isEqualTo("HIDDEN-SECRET");
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) all.filter(Files::isRegularFile)::iterator) {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content.contains("FUZZ-")) {
                    assertThat(p.startsWith(root)).as("a fuzz write landed outside the root: " + p).isTrue();
                    assertThat(root.relativize(p).toString()).as("a fuzz write landed in a hidden location: " + p).doesNotContain("/.").doesNotStartWith(".");
                }
            }
        }
    }

    private static String randomPath(Random random, String alphabet) {
        List<String> parts = new ArrayList<>();
        for (int i = 1 + random.nextInt(4); i > 0; i--) {
            StringBuilder sb = new StringBuilder();
            for (int j = random.nextInt(5); j >= 0; j--) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            parts.add(sb.toString());
        }
        return String.join(random.nextBoolean() ? "/" : "", parts);
    }
}
