package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R3.2 and R10.2 of loom-onboarding: the guide travels inside the jar, and an agent can be given it with no repository. */
class GuideCommandTest {

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> "yes", new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
    }

    int guide(String... args) {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        return GuideCommand.guide(c, env());
    }

    String stdout() {
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String repo(String relative) throws Exception {
        return Files.readString(Path.of("../..").resolve(relative));
    }

    @Test
    void withNothingItListsEveryPage() {
        assertThat(guide()).isZero();

        assertThat(stdout()).contains("weave guide <page>").contains("readme").contains("The Loom reference").contains("loom").contains("llms");
        for (Guide.Page p : Guide.pages()) assertThat(stdout()).contains(p.title());
    }

    @Test
    void aPagePrintsExactlyTheTextTheRepositoryHas() throws Exception {
        assertThat(guide("6")).isZero();
        assertThat(stdout()).isEqualTo(repo("docs/guide/06-build-the-workflow.md"));

        out.reset();
        assertThat(guide("loom")).isZero();
        assertThat(stdout()).isEqualTo(repo("loom/ai-agent4j-loom/LOOM_GUIDE.md"));

        out.reset();
        assertThat(guide("llms")).isZero();
        assertThat(stdout()).isEqualTo(repo("llms.txt"));
    }

    @Test
    void aPageCanBeAskedForByNumberNameOrFile() {
        for (String ask : List.of("6", "06", "build-the-workflow", "06-build-the-workflow", "06-build-the-workflow.md", "BUILD-THE-WORKFLOW")) {
            assertThat(Guide.find(ask)).as(ask).hasValueSatisfying(p -> assertThat(p.name()).isEqualTo("6"));
        }
        assertThat(Guide.find("10")).hasValueSatisfying(p -> assertThat(p.resource()).endsWith("10-best-practices.md"));
        assertThat(Guide.find("readme")).isPresent();
        assertThat(Guide.find("README.md")).isPresent();
        assertThat(Guide.find("LOOM_GUIDE")).hasValueSatisfying(p -> assertThat(p.name()).isEqualTo("loom"));
        assertThat(Guide.find("nope")).isEmpty();
    }

    @Test
    void anUnknownPageIsRefusedWithTheNearestName() {
        assertThat(guide("loon")).isEqualTo(2);

        assertThat(err.toString()).contains("there is no page named loon").contains("did you mean loom?").contains("weave guide");
    }

    @Test
    void everyMarkdownFileOfTheGuideIsAPageAndEveryPageHasItsText() throws Exception {
        List<String> files;
        try (Stream<Path> s = Files.list(Path.of("../../docs/guide"))) {
            files = s.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".md")).sorted().collect(Collectors.toList());
        }
        List<String> pages = Guide.pages().stream().map(Guide.Page::resource).filter(r -> r.startsWith("guide/") && r.endsWith(".md") && !r.endsWith("LOOM_GUIDE.md"))
                .map(r -> r.substring("guide/".length())).sorted().collect(Collectors.toList());

        assertThat(pages).isEqualTo(files);
        for (Guide.Page p : Guide.pages()) assertThat(Guide.text(p)).as(p.resource()).hasValueSatisfying(t -> assertThat(t).isNotBlank());
    }

    // ── the skill ────────────────────────────────────────────────────────────

    @Test
    void installSkillWritesTheSkillAndThePagesItPointsAtAndPointsThemAtReferences() throws Exception {
        assertThat(guide("--install-skill", dir.toString())).isZero();

        Path folder = Guide.skillFolder(dir);
        String skill = Files.readString(folder.resolve("SKILL.md"));
        assertThat(skill).startsWith("---\nname: llm4j-workflow-guide").contains("`references/").doesNotContain("`docs/guide/");
        for (Guide.Page p : Guide.pages()) {
            assertThat(folder.resolve("references").resolve(p.resource().substring("guide/".length()))).as(p.name()).exists();
        }
        assertThat(stdout()).contains("Installed the skill in").contains("14 files");
    }

    @Test
    void aSkillThatIsAlreadyThereIsNotReplacedUnlessAskedTo() throws Exception {
        guide("--install-skill", dir.toString());
        Path skill = Guide.skillFolder(dir).resolve("SKILL.md");
        Files.writeString(skill, "my edits");
        out.reset();

        assertThat(guide("--install-skill", dir.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("already in").contains("--force");
        assertThat(Files.readString(skill)).isEqualTo("my edits");

        assertThat(guide("--install-skill", dir.toString(), "--force")).isZero();
        assertThat(Files.readString(skill)).startsWith("---\nname:");
    }

    @Test
    void theInstalledChaptersLinkOnlyToEachOtherOrTheWeb() throws Exception {
        guide("--install-skill", dir.toString());
        Path references = Guide.skillFolder(dir).resolve("references");
        var link = java.util.regex.Pattern.compile("\\]\\(([^)\\s]+)\\)");
        try (Stream<Path> s = Files.list(references)) {
            for (Path chapter : s.filter(p -> p.toString().endsWith(".md") && !p.getFileName().toString().equals("LOOM_GUIDE.md")).toList()) {
                var m = link.matcher(Files.readString(chapter));
                while (m.find()) {
                    String t = m.group(1);
                    if (t.startsWith("http") || t.startsWith("#")) continue;
                    assertThat(references.resolve(t.replaceFirst("#.*", ""))).as(chapter.getFileName() + " -> " + t).exists();
                }
            }
        }
    }
}
