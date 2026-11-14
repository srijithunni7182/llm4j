package io.github.llm4j.tools.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The documents module adds one library to what an application gets (phase 35, D4 rule 2): PDFBox
 * (Apache 2.0, with fontbox, pdfbox-io and commons-logging, all Apache 2.0) beside the core, the light tools and Jackson.
 */
class WeightTest {

    static final Set<String> ALLOWED =
            Set.of(
                    "io.github.srijithunni7182:ai-agent4j:compile",
                    "io.github.srijithunni7182:ai-agent4j-tools:compile",
                    "com.fasterxml.jackson.core:jackson-databind:compile",
                    "org.apache.pdfbox:pdfbox:compile");

    @Test
    void theModuleAddsOnlyPdfBox() throws Exception {
        Path list = Path.of("target", "weight", "dependencies.txt");
        assertThat(list).isRegularFile();
        Set<String> shipped = new TreeSet<>();
        for (String raw : Files.readAllLines(list)) {
            String line = raw.replaceAll("\u001B\\[[0-9;]*m", "").strip();
            int note = line.indexOf(" -- ");
            if (note >= 0) line = line.substring(0, note).strip();
            String[] parts = line.split(":");
            if (parts.length < 5 || parts[parts.length - 1].startsWith("test")) continue;
            shipped.add(parts[0] + ":" + parts[1] + ":" + parts[parts.length - 1]);
        }
        assertThat(shipped).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }
}
