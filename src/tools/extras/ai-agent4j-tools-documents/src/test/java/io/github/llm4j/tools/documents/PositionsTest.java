package io.github.llm4j.tools.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D18 as amended: the text comes with positions (a PDF's 1-based page, a DOCX's paragraph, each
 * with character offsets), and slicing the text by a reported range returns exactly the text of
 * that part, read on its own. On a PDF and a DOCX written by the test, so their text is known.
 */
class PositionsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path dir;

    JsonNode read(String path, String... options) throws Exception {
        Map<String, String> o = new java.util.HashMap<>(Map.of("root", "."));
        for (int i = 0; i < options.length; i += 2) o.put(options[i], options[i + 1]);
        String out = new DocumentsKind().create("Docs", o, dir, EffectContext.noop()).execute(Map.of("path", path));
        assertThat(out).doesNotStartWith("Error:");
        return JSON.readTree(out);
    }

    @Test
    void aPdfsPartsArePagesNumberedFromOneAndEachSliceIsThatPagesText() throws Exception {
        Path pdf = Fixtures.pdf(dir.resolve("report.pdf"), List.of("First page: revenue 120.", "Second page: costs 80.", "Third."));
        JsonNode r = read("report.pdf");
        String text = r.get("text").asText();
        assertThat(r.get("format").asText()).isEqualTo("pdf");
        assertThat(r.get("parts")).hasSize(3);
        for (int i = 0; i < 3; i++) {
            JsonNode p = r.get("parts").get(i);
            assertThat(p.get("page").asInt()).isEqualTo(i + 1);
            String slice = text.substring(p.get("start").asInt(), p.get("end").asInt());
            assertThat(slice).isEqualTo(PdfText.page(pdf, i + 1));
        }
        String second = text.substring(r.get("parts").get(1).get("start").asInt(), r.get("parts").get(1).get("end").asInt());
        assertThat(second).contains("Second page: costs 80.").doesNotContain("First").doesNotContain("Third");
    }

    @Test
    void aDocxsPartsAreParagraphsAndEachSliceIsThatParagraphsText() throws Exception {
        Fixtures.docx(dir.resolve("memo.docx"),
                Fixtures.paragraph("Dear team,")
                        + "<w:p/>"
                        + "<w:p><w:r><w:t>Totals </w:t></w:r><w:r><w:t>rose</w:t><w:tab/><w:t>by 4%.</w:t></w:r></w:p>"
                        + "<w:tbl><w:tr><w:tc>" + Fixtures.paragraph("In a table") + "</w:tc></w:tr></w:tbl>"
                        + "<w:p><w:r><w:t>Line one</w:t><w:br/><w:t>line two</w:t></w:r></w:p>");
        JsonNode r = read("memo.docx");
        String text = r.get("text").asText();
        List<String> expected = List.of("Dear team,", "", "Totals rose\tby 4%.", "In a table", "Line one\nline two");
        assertThat(r.get("parts")).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode p = r.get("parts").get(i);
            assertThat(p.get("paragraph").asInt()).isEqualTo(i + 1);
            assertThat(text.substring(p.get("start").asInt(), p.get("end").asInt())).isEqualTo(expected.get(i));
        }
    }

    @Test
    void aCutTextKeepsItsPositionsTrue() throws Exception {
        Fixtures.docx(dir.resolve("long.docx"), Fixtures.paragraph("a".repeat(30)) + Fixtures.paragraph("b".repeat(30)) + Fixtures.paragraph("c".repeat(30)));
        JsonNode r = read("long.docx", "max_chars", "40");
        String text = r.get("text").asText();
        assertThat(r.get("truncated").asBoolean()).isTrue();
        assertThat(text).hasSize(40);
        assertThat(r.get("parts")).hasSize(2);
        JsonNode last = r.get("parts").get(1);
        assertThat(text.substring(last.get("start").asInt(), last.get("end").asInt())).isEqualTo("b".repeat(9));
    }
}
