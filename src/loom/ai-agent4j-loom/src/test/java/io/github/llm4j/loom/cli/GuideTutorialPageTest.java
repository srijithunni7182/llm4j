package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The tutorial travels inside the jar as one web page ({@code weave guide tutorial}), made from the
 * markdown in {@code docs/tutorial}. The page is generated and committed, so a test says when the
 * markdown has moved on and the page has not ({@code scripts/make-tutorial-html.sh} redraws it).
 */
class GuideTutorialPageTest {

    private static final Path TUTORIAL = Path.of("../../../docs/tutorial").toAbsolutePath().normalize();
    private static final Pattern SOURCE_HASH = Pattern.compile("<!-- source-sha256: ([0-9a-f]{64}) -->");

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int guide(String... args) {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        return GuideCommand.guide(
                c,
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        x -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null));
    }

    private static String page() {
        return Guide.read("guide/loom-tutorial.html").orElseThrow(() -> new AssertionError("the jar has no guide/loom-tutorial.html"));
    }

    /** The markdown the page was made from, in the order the tutorial reads: the index, then the chapters. */
    private static String sourceHash() throws Exception {
        List<Path> files;
        try (Stream<Path> all = Files.list(TUTORIAL)) {
            files = all.filter(p -> p.toString().endsWith(".md")).sorted(java.util.Comparator.comparing((Path p) -> !p.getFileName().toString().equals("README.md")).thenComparing(Path::getFileName)).toList();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path f : files) digest.update((f.getFileName() + "\n" + Files.readString(f, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void theTutorialPageIsListedAmongTheGuidePages() {
        assertThat(Guide.find("tutorial")).isPresent();
        assertThat(guide()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("tutorial");
    }

    @Test
    void thePageIsMadeFromTheMarkdownAsItIsNow() throws Exception {
        Matcher m = SOURCE_HASH.matcher(page());

        assertThat(m.find()).as("the page records the markdown it was made from").isTrue();
        assertThat(m.group(1)).as("docs/tutorial changed since the page was made: run scripts/make-tutorial-html.sh").isEqualTo(sourceHash());
    }

    @Test
    void thePageIsWholeAndDrawsEveryDiagramOfTheMarkdown() throws Exception {
        String html = page();
        long diagrams = 0;
        try (Stream<Path> all = Files.list(TUTORIAL)) {
            for (Path f : all.filter(p -> p.toString().endsWith(".md")).toList())
                diagrams += Pattern.compile("```mermaid").matcher(Files.readString(f)).results().count();
        }

        assertThat(html).startsWith("<!doctype html>").contains("</html>").contains("<title>The Loom Tutorial</title>");
        assertThat(Pattern.compile("<pre class=\"mermaid\">").matcher(html).results().count()).isEqualTo(diagrams);
        assertThat(html).contains("id=\"c01\"").contains("id=\"c14\"");
        assertThat(html).as("the page never prints a literal key").doesNotContainPattern("AIza[0-9A-Za-z_-]{20,}");
    }

    @Test
    void weaveGuideTutorialWritesThePageToTheFileNamed() throws Exception {
        Path target = dir.resolve("loom-tutorial.html");

        assertThat(guide("tutorial", "--out", target.toString())).isZero();

        assertThat(Files.readString(target)).startsWith("<!doctype html>");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains(target.toString()).contains("browser");
    }

    @Test
    void anExistingFileIsNotReplacedUnlessAskedTo() throws Exception {
        Path target = Files.writeString(dir.resolve("mine.html"), "keep me");

        assertThat(guide("tutorial", "--out", target.toString())).isEqualTo(2);
        assertThat(Files.readString(target)).isEqualTo("keep me");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("already exists").contains("--force");

        assertThat(guide("tutorial", "--out", target.toString(), "--force")).isZero();
        assertThat(Files.readString(target)).startsWith("<!doctype html>");
    }

    @Test
    void withoutAFileNameThePageGoesToLoomTutorialHtmlInTheCurrentFolder() {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs("tutorial");

        assertThat(c.tutorialFile().getName()).isEqualTo("loom-tutorial.html");
    }
}
