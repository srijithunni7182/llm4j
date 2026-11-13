package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.a2a.A2AException;
import io.github.llm4j.a2a.TaskState;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.TraceEvent;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The A2A documentation set in docs/a2a: indexed, linked, aimed at a reader, compiled and matching the code. */
class A2ADocsTest {

    private static final Path ROOT = Path.of("..", "..", "..");
    private static final Path A2A = ROOT.resolve("docs").resolve("a2a");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern COMPILES = Pattern.compile("<!-- compiles -->\\s*```java\\n(.*?)```", Pattern.DOTALL);

    @TempDir Path dir;

    private static List<Path> pages() throws Exception {
        try (Stream<Path> s = Files.list(A2A)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static String read(Path p) throws Exception {
        return Files.readString(p);
    }

    @Test
    void theReadmeListsEveryOtherPage() throws Exception {
        String readme = read(A2A.resolve("README.md"));
        for (Path page : pages()) {
            String name = page.getFileName().toString();
            if (!name.equals("README.md")) assertThat(readme).as(name).contains("](" + name + ")");
        }
    }

    @Test
    void everyPageNamesItsReaderAndLinksToTheReadmeAndThePhilosophy() throws Exception {
        for (Path page : pages()) {
            String name = page.getFileName().toString();
            String text = read(page);
            assertThat(text).as(name + " names its reader").contains("**Who this is for:**");
            if (!name.equals("README.md")) assertThat(text).as(name).contains("](README.md)");
            if (!name.equals("PHILOSOPHY.md")) assertThat(text).as(name).contains("](PHILOSOPHY.md)");
        }
    }

    @Test
    void everyRelativeLinkAndAnchorInTheSetResolves() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Path page : pages()) {
            Matcher m = LINK.matcher(read(page));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) continue;
                String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                String anchor = target.contains("#") ? target.substring(target.indexOf('#') + 1) : "";
                Path resolved = file.isEmpty() ? page : page.getParent().resolve(file).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(page.getFileName() + " -> " + target);
                } else if (!anchor.isEmpty() && resolved.toString().endsWith(".md") && !headings(resolved).contains(anchor)) {
                    broken.add(page.getFileName() + " -> " + target + " (no such heading)");
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    private static List<String> headings(Path file) throws Exception {
        List<String> slugs = new ArrayList<>();
        for (String line : read(file).split("\n")) {
            if (line.startsWith("#")) {
                String title = line.replaceFirst("^#+\\s*", "").toLowerCase(Locale.ROOT);
                slugs.add(title.replaceAll("[^a-z0-9 _-]", "").trim().replace(' ', '-'));
            }
        }
        return slugs;
    }

    @Test
    void theJavaExcerptsMarkedAsCompilingCompileAgainstTheRealClasses() throws Exception {
        List<String> paths = new ArrayList<>();
        for (Path page : pages()) {
            Matcher m = COMPILES.matcher(read(page));
            while (m.find()) {
                String source = m.group(1);
                String pkg = find(source, "package (.+?);");
                String type = find(source, "public (?:final )?(?:class|interface|record) (\\w+)");
                Path file = dir.resolve("src").resolve(pkg.replace('.', '/')).resolve(type + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source);
                paths.add(file.toString());
            }
        }
        assertThat(paths).as("the Java guide has a compiled example").isNotEmpty();
        Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-cp", System.getProperty("java.class.path"), "-d", dir.resolve("out").toString()));
        args.addAll(paths);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new))).isZero();
    }

    private static String find(String source, String regex) {
        Matcher m = Pattern.compile(regex).matcher(source);
        assertThat(m.find()).as(regex).isTrue();
        return m.group(1);
    }

    @Test
    void theReferenceListsEveryTaskStateAndEveryErrorType() throws Exception {
        String reference = read(A2A.resolve("REFERENCE.md"));
        for (TaskState state : TaskState.values()) assertThat(reference).as(state.name()).contains("`" + state.name() + "`");
        for (Class<?> nested : A2AException.class.getDeclaredClasses()) assertThat(reference).as(nested.getSimpleName()).contains("`" + nested.getSimpleName() + "`");
    }

    private static final Pattern LOOM_BLOCK = Pattern.compile("```loom\\n(.*?)```", Pattern.DOTALL);

    @Test
    void everyLoomBlockOnTheLoomPageAndInTheGuidesA2ASectionParsesAndChecksWithNoError() throws Exception {
        String guide = read(Path.of("LOOM_GUIDE.md"));
        String section = guide.substring(guide.indexOf("### Calling an agent that someone else runs"), guide.indexOf("### Sending pictures, recordings and documents"));
        List<String> blocks = new ArrayList<>();
        for (String text : List.of(read(A2A.resolve("LOOM.md")), section)) {
            Matcher m = LOOM_BLOCK.matcher(text);
            while (m.find()) blocks.add(m.group(1));
        }
        assertThat(blocks).as("the Loom page and the guide each show a script").hasSizeGreaterThanOrEqualTo(2);
        for (String block : blocks) {
            var script = new LoomParser(new Lexer(block).tokenize()).parseScript();
            var problems = new ScriptValidator().validate(script, new ScriptValidator.Context().registeredTools(java.util.Set.of()).baseDir(Path.of(".")));
            assertThat(problems.stream().filter(p -> p.severity() == ScriptValidator.Severity.ERROR).map(ScriptValidator.Problem::message))
                    .as(block).isEmpty();
        }
    }

    @Test
    void theReferenceListsEveryLoomSettingItsDefaultsAndEveryTraceEvent() throws Exception {
        String reference = read(A2A.resolve("REFERENCE.md"));
        for (String setting : List.of("card", "auth", "agent_channel", "timeout", "require_signed_card", "audience", "max_questions", "expecting", "RemoteTasks.taskChanged", "RunSuspended.Reason.REMOTE"))
            assertThat(reference).as(setting).contains(setting);
        assertThat(reference).contains("`" + TraceEvent.REMOTE + "`").contains("`" + TraceEvent.EXCHANGE + "`");
        assertThat(reference).contains("| 3 |").contains("1h");
        assertThat(read(A2A.resolve("LOOM.md"))).contains("remotes:").contains("channels:").contains("virtual time");
        assertThat(read(Path.of("LOOM_GUIDE.md"))).contains("`remotes:`, `channels:`");
    }

    @Test
    void theServeProtocolDocListsTheNewSymbolKeysAndRoles() throws Exception {
        String protocol = read(ROOT.resolve("docs/serve/PROTOCOL.md"));
        for (String word : List.of("`remote:`", "`channel:`", "`expose:`", "remote-channel", "expose-channel", "expose-workflow", "`ask`"))
            assertThat(protocol).as(word).contains(word);
    }

    @Test
    void thePrinciplesFileIsAPointerThatResolves() throws Exception {
        Path pointer = ROOT.resolve("docs/specs/32-a2a-integration/PRINCIPLES.md");
        Matcher m = LINK.matcher(read(pointer));
        int links = 0;
        while (m.find()) {
            links++;
            assertThat(pointer.getParent().resolve(m.group(1)).normalize()).exists();
        }
        assertThat(links).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theReadmesOfAffectedProjectsLinkTheSet() throws Exception {
        for (String readme : List.of("README.md", "docs/README.md", "src/ai-agent4j/README.md", "src/tools/ai-agent4j-tools/README.md", "src/spring/llm4j-spring-boot-starter/README.md", "src/ai-agent4j/wiki/A2A.md")) {
            assertThat(read(ROOT.resolve(readme))).as(readme).containsAnyOf("docs/a2a/", "a2a/README.md");
        }
        assertThat(read(ROOT.resolve("src/ai-agent4j/wiki/Home.md"))).contains("(A2A.md)");
        assertThat(read(ROOT.resolve("llms.txt"))).contains("docs/a2a/README.md");
        assertThat(read(ROOT.resolve("docs/API_COMPATIBILITY.md"))).contains("io.github.llm4j.a2a");
    }
}
