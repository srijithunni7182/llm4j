package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 37: everything llm4j can do is documented, and that documentation ships in the jar for the Guide. The shipped pages
 * are the repository's own, every user-facing folder ships whole, a search in the developer's words finds the right section,
 * and what the Guide reads is bounded.
 */
class GuideLibraryTest {

    private static final Path REPO = Path.of("../../..");

    private static List<String> markdown(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(REPO.resolve(dir))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md")).sorted().toList();
        }
    }

    @Test
    void everyUserFacingFolderShipsWhole() throws Exception {
        List<String> pages = GuideLibrary.pages();
        for (var folder : List.of(
                List.of("wiki", "library/loom-wiki/"),
                List.of("src/ai-agent4j/wiki", "library/core-wiki/"),
                List.of("src/tools/ai-agent4j-tools/docs", "library/tools/"),
                List.of("docs/security", "library/security/"),
                List.of("docs/guide", "guide/")))
            for (String name : markdown(Path.of(folder.get(0))))
                assertThat(pages).as(folder.get(0) + "/" + name).contains(folder.get(1) + name);
        assertThat(pages).contains(
                "guide/LOOM_GUIDE.md", "catalog/TOOLS_CATALOG.md", "library/docs/THE_STACK.md", "library/docs/COMMAND_REFERENCE.md",
                "library/modules/eval4j/README.md", "library/modules/eval4j-report/docs/USER-GUIDE.md", "library/modules/loom-fabric/README.md",
                "library/modules/spring-boot-starter/README.md", "library/modules/starters/README.md");
    }

    @Test
    void theShippedPagesAreTheRepositorysOwn() throws Exception {
        assertThat(GuideLibrary.text("library/loom-wiki/The-Loom-Guide.md")).get().isEqualTo(Files.readString(REPO.resolve("wiki/The-Loom-Guide.md")));
        assertThat(GuideLibrary.text("library/security/" + markdown(Path.of("docs/security")).get(0))).get()
                .isEqualTo(Files.readString(REPO.resolve("docs/security").resolve(markdown(Path.of("docs/security")).get(0))));
        assertThat(GuideLibrary.text("../pom.xml")).isEmpty();
        assertThat(GuideLibrary.text("library/nope.md")).isEmpty();
    }

    @Test
    void aSearchInTheDevelopersWordsFindsTheRightSection() {
        assertThat(GuideLibrary.search("use an MCP server for my agent")).first().satisfies(h ->
                assertThat((h.page() + " " + h.heading()).toLowerCase()).contains("mcp"));
        assertThat(GuideLibrary.search("call a REST API with an openapi spec").stream().map(h -> h.page() + " " + h.heading()).toList())
                .anyMatch(s -> s.toLowerCase().contains("openapi"));
        assertThat(GuideLibrary.search("retrieval augmented generation rag documents").stream().map(GuideLibrary.Hit::page).toList())
                .anyMatch(p -> p.toLowerCase().contains("rag"));
        assertThat(GuideLibrary.search("mask personal data pii").stream().map(h -> (h.page() + h.heading()).toLowerCase()).toList())
                .anyMatch(s -> s.contains("pii"));
        assertThat(GuideLibrary.search("a")).isEmpty();
        assertThat(GuideLibrary.search("zzqx-nothing-matches")).isEmpty();
        assertThat(GuideLibrary.search("agent")).hasSizeLessThanOrEqualTo(GuideLibrary.MAX_HITS);
    }

    @Test
    void aPageGivesItsHeadingsAndOneSectionWithItsSubsections() {
        String outline = GuideLibrary.outline("catalog/TOOLS_CATALOG.md");
        assertThat(outline).contains("Tools catalog").contains("`openapi`");
        String section = GuideLibrary.section("catalog/TOOLS_CATALOG.md", "Connect to anything").orElseThrow();
        assertThat(section).contains("`openapi`").contains("`mcp`");
        assertThat(GuideLibrary.section("catalog/TOOLS_CATALOG.md", "No such heading")).isEmpty();
        String big = GuideLibrary.section("guide/LOOM_GUIDE.md", "3. Deep Dive: Agent Configuration").orElseThrow();
        assertThat(big.length()).isLessThanOrEqualTo(GuideLibrary.MAX_SECTION_CHARS + 60);
    }

    @Test
    void aHeadingInsideACodeBlockIsNotAHeading() {
        // LOOM_GUIDE has a policy example whose lines start with "## " inside a fenced block
        assertThat(GuideLibrary.sections("guide/LOOM_GUIDE.md").stream().map(GuideLibrary.Section::heading)).doesNotContain("no_refund_promise");
    }

    @Test
    void theDocsToolAndTheCommandLineGiveTheSameLookups() {
        assertThat(GuideMcpServer.docs("", "", "")).contains("library/core-wiki/").contains("catalog/TOOLS_CATALOG.md");
        assertThat(GuideMcpServer.docs("openapi operation effect", "", "")).startsWith("Best sections");
        assertThat(GuideMcpServer.docs("zzqx-nothing", "", "")).contains("Nothing in the documentation matches");
        assertThat(GuideMcpServer.docs("", "catalog/TOOLS_CATALOG.md", "")).contains("At a glance");
        assertThatThrownBy(() -> GuideMcpServer.docs("", "../../etc/passwd", "")).hasMessageContaining("No page");
        assertThatThrownBy(() -> GuideMcpServer.docs("", "catalog/TOOLS_CATALOG.md", "Nope")).hasMessageContaining("Its headings");

        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        WeaveEnv env = WeaveEnv.system().withStreams(new java.io.PrintStream(out, true), new java.io.PrintStream(err, true));
        assertThat(GuideFactsCommand.docs("mcp server", null, null, env)).isZero();
        assertThat(out.toString()).contains("Best sections");
        assertThat(GuideFactsCommand.docs(null, "nope.md", null, env)).isEqualTo(2);
    }
}
