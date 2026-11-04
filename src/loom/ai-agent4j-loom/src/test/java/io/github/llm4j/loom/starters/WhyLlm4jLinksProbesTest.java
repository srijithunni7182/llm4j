package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Phase 29, R4: the new rows of docs/WHY_LLM4J.md name another framework only where they link a probe section. */
class WhyLlm4jLinksProbesTest {

    private static final Path WHY = Path.of("../../../docs/WHY_LLM4J.md").toAbsolutePath().normalize();
    private static final Path PROBES = Path.of("../../../probes/README.md").toAbsolutePath().normalize();

    private static List<String> mistakeRows() throws Exception {
        String text = Files.readString(WHY);
        int start = text.indexOf("### 🏗️ Architecture mistakes");
        int end = text.indexOf("<sub>Compared with the public documentation");
        assertThat(start).isPositive();
        assertThat(end).isGreaterThan(start);
        return text.substring(start, end).lines().filter(l -> l.startsWith("| **")).toList();
    }

    @Test
    void thereIsARowForEachOfTheThirteenMistakesAndTheTopLinksThePage() throws Exception {
        assertThat(mistakeRows()).hasSizeGreaterThanOrEqualTo(13);
        assertThat(Files.readString(WHY)).contains("The idea behind these guarantees").contains("(REVIEWABLE_ARCHITECTURE.md)");
    }

    @Test
    void aRowThatSaysSomethingAboutAnotherFrameworkLinksAProbeSection() throws Exception {
        for (String row : mistakeRows()) {
            String[] cells = row.split("\\|");
            String other = cells[cells.length - 1].isBlank() ? cells[cells.length - 2] : cells[cells.length - 1];
            if (other.contains("No probe, no claim") || other.contains("No new probe")) continue;
            assertThat(other).as(row).contains("probes/README.md#p");
        }
    }

    @Test
    void everyProbeLinkInThePageResolves() throws Exception {
        Set<String> anchors = new HashSet<>();
        for (String line : Files.readString(PROBES).split("\\R"))
            if (line.startsWith("#")) anchors.add(line.replaceFirst("^#+\\s*", "").toLowerCase().replaceAll("[^a-z0-9 -]", "").replace(' ', '-'));
        Matcher m = Pattern.compile("probes/README\\.md#([a-z0-9-]+)").matcher(Files.readString(WHY));
        while (m.find()) assertThat(anchors).as(m.group(1)).contains(m.group(1));
    }
}
