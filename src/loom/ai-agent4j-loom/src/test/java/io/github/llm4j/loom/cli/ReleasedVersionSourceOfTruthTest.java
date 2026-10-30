package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.init.ReleasedVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Between releases {@code main} carries the next {@code -SNAPSHOT} version, so three things that
 * must name a version someone can download read the last published one from a single place: the
 * {@code llm4j.released} property of the parent pom. If they disagree, the catalogue sends people
 * to a jar that does not exist, or a generated project depends on a snapshot nobody published.
 */
class ReleasedVersionSourceOfTruthTest {
    private static final Path ROOT = Path.of("../../../").toAbsolutePath().normalize();

    private static String releasedInThePom() throws Exception {
        Matcher m =
                Pattern.compile("<llm4j.released>([^<]+)</llm4j.released>")
                        .matcher(Files.readString(ROOT.resolve("pom.xml")));
        assertThat(m.find()).as("the parent pom defines llm4j.released").isTrue();
        return m.group(1);
    }

    @Test
    void theJbangCatalogueNamesTheReleasedVersionNotTheSnapshot() throws Exception {
        String ref =
                new ObjectMapper()
                        .readTree(ROOT.resolve("jbang-catalog.json").toFile())
                        .path("aliases")
                        .path("weave")
                        .path("script-ref")
                        .asText();

        assertThat(ref)
                .isEqualTo("io.github.srijithunni7182:ai-agent4j-loom:" + releasedInThePom() + ":cli");
        assertThat(ref).doesNotContain("SNAPSHOT");
    }

    @Test
    void theCodeReadsTheSameReleasedVersionTheBuildWasGiven() throws Exception {
        assertThat(ReleasedVersion.get()).isEqualTo(releasedInThePom());
    }

    @Test
    void aGeneratedProjectDependsOnTheReleasedVersion() throws Exception {
        String pom =
                new String(
                        ReleasedVersionSourceOfTruthTest.class
                                .getResourceAsStream("/templates/_java-tests/pom.xml")
                                .readAllBytes());

        assertThat(pom)
                .contains("<version>" + releasedInThePom() + "</version>")
                .doesNotContain("@llm4j.released@")
                .doesNotContain("SNAPSHOT");
    }

    @Test
    void theDefaultFabricForADeploymentIsTheReleasedOne() {
        assertThat(io.github.llm4j.loom.deploy.DeployOptions.DEFAULT_FABRIC)
                .isEqualTo("loom-fabric:" + ReleasedVersion.get());
    }
}
