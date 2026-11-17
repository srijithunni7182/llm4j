package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Phase 37a: everything the Guide may say about llm4j is held to what exists. Each fact names a
 * command that exists, an editor command the extension has, a page and heading that exist, and
 * what it does not do; each moment names a fact and a known trigger; each stage has its texts.
 */
class GuideCatalogueTest {

    private static final Path REPO = Path.of("../../..").toAbsolutePath().normalize();
    private static final int MAX_WORDS_PER_TEXT = 60;

    @Test
    void everyStageOfTheJourneyHasItsFourTextsAndTheyAreShort() {
        assertThat(StageTexts.all().keySet()).containsExactlyElementsOf(JourneyStages.IDS);
        StageTexts.all()
                .forEach(
                        (id, t) -> {
                            for (String text : List.of(t.why(), t.see(), t.decision(), t.missing())) {
                                assertThat(text).as(id).isNotBlank();
                                assertThat(text.split("\\s+").length).as(id + ": " + text).isLessThanOrEqualTo(MAX_WORDS_PER_TEXT);
                            }
                        });
    }

    @Test
    void everyFactSaysWhatItDoesNotDo() {
        assertThat(GuideCatalogue.facts()).isNotEmpty();
        GuideCatalogue.facts().values().forEach(f -> {
            assertThat(f.title()).as(f.id()).isNotBlank();
            assertThat(f.purpose()).as(f.id()).isNotBlank();
            assertThat(f.solves()).as(f.id()).isNotBlank();
            assertThat(f.not()).as(f.id() + " must say what it does not do").isNotBlank();
        });
    }

    @Test
    void everyFactsCommandIsAWeaveCommand() {
        Map<String, CommandLine> commands = WeaveCLI.commandLine().getSubcommands();
        for (GuideCatalogue.Fact f : GuideCatalogue.facts().values()) {
            if (f.command().isEmpty()) continue;
            String[] words = f.command().split("\\s+");
            assertThat(words[0]).as(f.id()).isEqualTo("weave");
            assertThat(commands).as(f.id() + ": " + f.command()).containsKey(words[1]);
        }
    }

    @Test
    void everyFactsEditorCommandIsOneTheExtensionHas() throws Exception {
        JsonNode pkg = new ObjectMapper().readTree(REPO.resolve("src/loom/vscode-loom/package.json").toFile());
        Set<String> titles = new java.util.HashSet<>();
        pkg.path("contributes").path("commands").forEach(c -> titles.add(c.path("title").asText()));
        for (GuideCatalogue.Fact f : GuideCatalogue.facts().values())
            if (!f.editor().isEmpty()) assertThat(titles).as(f.id()).contains(f.editor());
    }

    @Test
    void everyFactsPageAndHeadingExist() throws Exception {
        for (GuideCatalogue.Fact f : GuideCatalogue.facts().values()) {
            Path page = REPO.resolve(f.docFile());
            assertThat(page).as(f.id()).isRegularFile();
            if (f.docHeading().isEmpty()) continue;
            Set<String> headings = Files.readAllLines(page).stream()
                    .filter(l -> l.startsWith("#"))
                    .map(l -> l.replaceFirst("^#+\\s*", "").replaceAll("^[^\\p{L}\\p{N}\"`]+\\s*", "").strip())
                    .collect(Collectors.toSet());
            assertThat(headings).as(f.id() + " links to " + f.doc()).contains(f.docHeading());
        }
    }

    @Test
    void everyMomentNamesAFactAndAKnownTrigger() {
        assertThat(GuideCatalogue.moments()).isNotEmpty();
        for (GuideCatalogue.Moment m : GuideCatalogue.moments()) {
            assertThat(GuideCatalogue.facts()).as(m.id()).containsKey(m.fact());
            assertThat(m.show()).as(m.id()).isNotBlank();
            if (!m.project().isEmpty()) assertThat(GuideContext.TRIGGERS).as(m.id()).contains(m.project());
            else assertThat(m.words()).as(m.id() + " has no trigger and no words").isNotEmpty();
        }
    }

    @Test
    void weaveGuideFactsListsThemAndRefusesAnUnknownOne() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        WeaveEnv env = WeaveEnv.system().withStreams(new java.io.PrintStream(out, true), new java.io.PrintStream(err, true));

        assertThat(GuideFactsCommand.run(null, true, env)).isZero();
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertThat(json.path("facts")).hasSize(GuideCatalogue.facts().size());
        assertThat(json.path("moments")).hasSize(GuideCatalogue.moments().size());
        assertThat(json.path("facts").get(0).path("doc").asText()).startsWith(GuideCatalogue.DOCS_BASE);

        assertThat(GuideFactsCommand.run("warp-drive", false, env)).isEqualTo(2);
        assertThat(err.toString()).contains("golden-dataset");
    }
}
