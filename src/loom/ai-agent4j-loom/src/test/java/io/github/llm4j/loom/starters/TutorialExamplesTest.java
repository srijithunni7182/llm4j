package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.WeaveCLI;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tutorial ({@code docs/tutorial}) is checked like code. Every {@code loom} block loads with
 * {@code weave check} and without warnings; a {@code loom-error} block fails the check; a {@code
 * loom-parse} block parses; a Java block marked {@code <!-- compiles -->} compiles against the
 * current classes; every diagram is of a known kind; and every link, with its heading anchor,
 * resolves. So an example you copy from it is an example that works.
 */
class TutorialExamplesTest {

    private static final Path TUTORIAL = Path.of("../../../docs/tutorial").toAbsolutePath().normalize();
    private static final Path REPO = Path.of("../../..").toAbsolutePath().normalize();

    private static final int CHAPTERS = 14;

    private static final Pattern LOOM_BLOCK = Pattern.compile("```(loom|loom-error|loom-parse)\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern JAVA_BLOCK = Pattern.compile("<!-- compiles -->\\s*```java\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern MERMAID_BLOCK = Pattern.compile("```mermaid\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]]*\\]\\(([^)\\s]+)\\)");
    private static final Set<String> DIAGRAM_KINDS = Set.of("flowchart", "sequenceDiagram", "stateDiagram-v2", "graph", "classDiagram", "gantt", "erDiagram");

    @TempDir Path dir;

    private static List<Path> pages() throws Exception {
        try (Stream<Path> all = Files.list(TUTORIAL)) {
            return all.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    @Test
    void thereIsAnIndexAndFourteenChaptersInOrderEachLeadingToTheNext() throws Exception {
        String index = Files.readString(TUTORIAL.resolve("README.md"));
        List<String> names = pages().stream().map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d\\d-.*")).toList();

        assertThat(names).hasSize(CHAPTERS);
        for (int i = 0; i < names.size(); i++) {
            assertThat(names.get(i)).startsWith(String.format("%02d-", i + 1));
            assertThat(index).as("the index lists " + names.get(i)).contains("(" + names.get(i) + ")");
            String page = Files.readString(TUTORIAL.resolve(names.get(i)));
            if (i + 1 < names.size()) assertThat(page).as(names.get(i) + " leads to the next chapter").contains("Next: [").contains("](" + names.get(i + 1) + ")");
            assertThat(page).as(names.get(i) + " ends with something to try or the way back").containsAnyOf("## Try it yourself", "Back to the [tutorial index]");
        }
    }

    @Test
    void everyLoomBlockLoadsWithoutWarningsAndEveryErrorBlockFails() throws Exception {
        int checked = 0;
        for (Path page : pages()) {
            Matcher m = LOOM_BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            int n = 0;
            while (m.find()) {
                String kind = m.group(1);
                String code = m.group(2);
                String where = page.getFileName() + " block " + (++n) + " (" + kind + "):\n" + code;
                if (kind.equals("loom-parse")) {
                    new LoomParser(new Lexer(code).tokenize()).parseScript();
                    continue;
                }
                Path file = Files.writeString(dir.resolve("block-" + checked++ + ".loom"), code);
                Output out = weave("check", file.toString(), "--no-env");
                if (kind.equals("loom")) {
                    assertThat(out.exit).as(where + "\n→ " + out.text).isZero();
                    assertThat(out.text).as(where + "\n→ " + out.text).doesNotContain("warning");
                } else {
                    assertThat(out.exit).as(where + "\nshould not load").isNotZero();
                }
            }
        }
        assertThat(checked).as("blocks checked").isGreaterThan(40);
    }

    @Test
    void everyMarkedJavaBlockCompilesAgainstTheCurrentClasses() throws Exception {
        List<String> paths = new ArrayList<>();
        for (Path page : pages()) {
            Matcher m = JAVA_BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (m.find()) {
                String source = m.group(1);
                String pkg = first(source, "package (.+?);");
                String type = first(source, "public (?:final )?(?:class|interface|record) (\\w+)");
                Path file = dir.resolve("src").resolve(pkg.replace('.', '/')).resolve(type + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source);
                paths.add(file.toString());
            }
        }
        assertThat(paths).as("the Java chapter has examples").hasSizeGreaterThanOrEqualTo(5);
        Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-cp", System.getProperty("java.class.path"), "-d", dir.resolve("out").toString()));
        args.addAll(paths);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).as("the tutorial's Java examples compile").isZero();
    }

    @Test
    void everyDiagramIsOfAKnownKindAndBalanced() throws Exception {
        int diagrams = 0;
        for (Path page : pages()) {
            Matcher m = MERMAID_BLOCK.matcher(Files.readString(page, StandardCharsets.UTF_8));
            while (m.find()) {
                diagrams++;
                String body = m.group(1).strip();
                String kind = body.split("[\\s;]", 2)[0];
                assertThat(DIAGRAM_KINDS).as(page.getFileName() + " diagram kind '" + kind + "'").contains(kind);
                assertThat(blockOpeners(body)).as(page.getFileName() + ": every subgraph, alt, loop, opt or par has an end").isEqualTo(countLines(body, "end"));
                assertThat(count(body, "[")).as(page.getFileName() + ": brackets balance").isEqualTo(count(body, "]"));
                assertThat(count(body, "{")).as(page.getFileName() + ": braces balance").isEqualTo(count(body, "}"));
            }
        }
        assertThat(diagrams).as("the tutorial has diagrams").isGreaterThanOrEqualTo(20);
    }

    @Test
    void everyRelativeLinkAndItsHeadingAnchorResolves() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Path page : pages()) {
            Matcher m = LINK.matcher(withoutCode(Files.readString(page, StandardCharsets.UTF_8)));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) continue;
                String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                String anchor = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;
                Path resolved = path.isEmpty() ? page : page.getParent().resolve(path).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(page.getFileName() + " → " + target + " (no such file)");
                    continue;
                }
                if (anchor != null && Files.isRegularFile(resolved) && resolved.toString().endsWith(".md") && !anchors(resolved).contains(anchor))
                    broken.add(page.getFileName() + " → " + target + " (no such heading)");
            }
        }
        assertThat(broken).as("broken links").isEmpty();
    }

    @Test
    void theCommandsAndOptionsTheTutorialTeachesExist() throws Exception {
        String manual = Files.readString(REPO.resolve("docs/COMMAND_REFERENCE.md"));
        for (String command : List.of("check", "test", "run", "resume", "graph", "explain", "audit", "review", "eval", "init", "next", "package", "deploy",
                "fabric", "schedule", "daemon", "tick", "triggers", "timeline", "rewind", "reset", "fork", "capture", "regress", "gate", "metamorphic", "judge",
                "security", "probe", "autonomy", "replay", "secrets", "questions", "answer", "bindgen", "man", "guide", "rules"))
            assertThat(manual).as("COMMAND_REFERENCE documents weave " + command).contains("weave " + command);
    }

    private static String first(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    /** Lines that open a block a later {@code end} must close. */
    private static long blockOpeners(String diagram) {
        return diagram.lines().map(String::strip).filter(l -> l.matches("(subgraph|alt|loop|opt|par|critical|break|rect)\\b.*")).count();
    }

    private static long countLines(String text, String word) {
        return text.lines().filter(l -> l.strip().equals(word)).count();
    }

    /** The page without its fenced blocks, so that text inside a code sample is not read as a link. */
    private static String withoutCode(String markdown) {
        return markdown.replaceAll("(?s)```.*?```", "");
    }

    private static Set<String> anchors(Path markdown) throws Exception {
        Set<String> out = new HashSet<>();
        boolean inCode = false;
        for (String line : Files.readAllLines(markdown, StandardCharsets.UTF_8)) {
            if (line.startsWith("```")) inCode = !inCode;
            if (inCode || !line.startsWith("#")) continue;
            String title = line.replaceFirst("^#+\\s*", "").strip();
            out.add(slug(title));
        }
        return out;
    }

    /** GitHub's heading anchor: lower case, punctuation dropped, spaces to hyphens. */
    static String slug(String title) {
        StringBuilder b = new StringBuilder();
        for (char c : title.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') b.append(c);
            else if (c == ' ') b.append('-');
        }
        return b.toString();
    }

    private record Output(int exit, String text) {}

    /** Runs a weave command in this process and keeps what it printed. */
    private static synchronized Output weave(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        try {
            int exit = WeaveCLI.run(args);
            return new Output(exit, captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
