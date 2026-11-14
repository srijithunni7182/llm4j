package io.github.llm4j.tools.documents;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** The documents the tests read, written by the tests so their text is known exactly. */
final class Fixtures {

    private Fixtures() {}

    /** A PDF with one line of text per page. */
    static Path pdf(Path file, List<String> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String line : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.beginText();
                    s.setFont(font, 12);
                    s.newLineAtOffset(72, 700);
                    s.showText(line);
                    s.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A DOCX whose body holds {@code bodyXml} inside {@code w:body}. */
    static Path docx(Path file, String bodyXml) throws IOException {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                        + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                        + bodyXml
                        + "</w:body></w:document>";
        return zip(file, DocxText.BODY, xml.getBytes(StandardCharsets.UTF_8));
    }

    static String paragraph(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
    }

    static Path zip(Path file, String entry, byte[] content) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content);
            zip.closeEntry();
        }
        return file;
    }
}
