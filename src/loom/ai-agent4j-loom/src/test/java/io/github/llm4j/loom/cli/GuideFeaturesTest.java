package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase 37: what Loom does as a workflow language, for the Guide. Every feature says when a developer needs it, how Loom does
 * it, what it saves and what it does not do; its section and its tested pattern ship in the jar; and every construct it names
 * is one the language reference or the patterns really use, so the Guide's enthusiasm never names a thing Loom lacks.
 */
class GuideFeaturesTest {

    @Test
    void everyFeatureSaysWhenHowWhatItSavesAndWhatItDoesNot() {
        assertThat(GuideCatalogue.features()).hasSizeGreaterThanOrEqualTo(20)
                .containsKeys("parallel", "broadcast", "steering", "supervise", "earned-autonomy", "approval-at-the-edge", "schedule", "checkpoints-rewind");
        for (GuideCatalogue.Feature f : GuideCatalogue.features().values()) {
            assertThat(f.title()).as(f.id()).isNotBlank();
            assertThat(f.when()).as(f.id() + " needs the words a developer uses").hasSizeGreaterThanOrEqualTo(3);
            assertThat(f.how()).as(f.id()).isNotBlank();
            assertThat(f.saves()).as(f.id()).isNotBlank();
            assertThat(f.not()).as(f.id() + " must say what it does not do").isNotBlank();
        }
    }

    @Test
    void everyFeaturesSectionAndPatternShipInTheJar() {
        for (GuideCatalogue.Feature f : GuideCatalogue.features().values()) {
            assertThat(GuideLibrary.section(f.docPage(), f.docHeading())).as(f.id() + " -> " + f.doc()).isPresent();
            if (!f.pattern().isEmpty())
                assertThat(GuideLibrary.section(GuideCatalogue.PATTERNS, f.pattern())).as(f.id() + " pattern " + f.pattern()).isPresent();
        }
    }

    @Test
    void everyConstructAFeatureNamesIsOneLoomReallyHas() {
        String known = GuideLibrary.text("guide/LOOM_GUIDE.md").orElseThrow() + GuideLibrary.text(GuideCatalogue.PATTERNS).orElseThrow();
        Matcher m;
        for (GuideCatalogue.Feature f : GuideCatalogue.features().values()) {
            m = Pattern.compile("`([^`]+)`").matcher(f.how());
            while (m.find()) {
                String construct = m.group(1).replace("...", "").replaceAll("\\s+", " ").strip();
                // a construct with an argument is checked by its first word: `use Tool(...) approve "..."` by `use`
                String head = construct.split("[ (]")[0];
                assertThat(known).as(f.id() + " names `" + construct + "`").contains(head);
            }
        }
    }

    @Test
    void theGuideListsTheFeaturesAndReadsOneInFull() throws Exception {
        JsonNode list = new ObjectMapper().readTree(GuideMcpServer.feature("list"));
        assertThat(list.path("features")).hasSize(GuideCatalogue.features().size());
        JsonNode one = new ObjectMapper().readTree(GuideMcpServer.feature("broadcast"));
        assertThat(one.path("how").asText()).contains("broadcast");
        assertThat(one.path("read").asText()).startsWith("docs page=guide/LOOM_GUIDE.md");
        JsonNode withPattern = new ObjectMapper().readTree(GuideMcpServer.feature("parallel"));
        assertThat(withPattern.path("pattern").asText()).contains(GuideCatalogue.PATTERNS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> GuideMcpServer.feature("teleport")).hasMessageContaining("earned-autonomy");

        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        WeaveEnv env = WeaveEnv.system().withStreams(new java.io.PrintStream(out, true), new java.io.PrintStream(err, true));
        assertThat(GuideFactsCommand.feature("steering", env)).isZero();
        assertThat(out.toString()).contains("steerable");
        assertThat(GuideFactsCommand.feature("teleport", env)).isEqualTo(2);
    }
}
