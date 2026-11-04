package io.github.llm4j.loom.review.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R4 of docs/specs/28: a fix that makes an extractor says which cases it now needs. */
class GoldenCasesNoteTest {

    @TempDir Path dir;

    @Test
    void withNoCasesItNamesTheFileAndTheCommand() {
        String note = GoldenCasesNote.forNewExtractor(dir, "InvoiceFields", "Invoice");
        assertThat(note)
                .contains("eval/golden/invoicefields.yaml")
                .contains("weave check")
                .contains("--init")
                .doesNotContain("are for an agent");
    }

    @Test
    void withCasesWrittenForTheAgentItSaysToRewriteThem() throws Exception {
        Files.createDirectories(dir.resolve("eval/golden"));
        Files.writeString(dir.resolve("eval/golden/invoice.yaml"), "- id: a\n  input: x\n");
        assertThat(GoldenCasesNote.forNewExtractor(dir, "InvoiceFields", "Invoice"))
                .contains("The cases written for Invoice are for an agent")
                .contains("expected_fields");
    }

    @Test
    void aMissingBaseDirIsNotAnError() {
        assertThat(GoldenCasesNote.forNewExtractor(null, "X", "X")).contains("eval/golden/x.yaml");
    }
}
