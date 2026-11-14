package io.github.llm4j.tools.documents;

import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/** A PDF's text, page by page, through PDFBox; scratch data goes to temporary files, not memory. */
final class PdfText {

    private PdfText() {}

    static Extracted read(Path file, int maxPages, int maxChars) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setSortByPosition(true);
            Extracted.Builder out = new Extracted.Builder("page");
            boolean truncated = pages > maxPages;
            for (int page = 1; page <= Math.min(pages, maxPages); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                out.add(page, stripper.getText(doc));
                if (out.length() > maxChars) {
                    truncated = true;
                    break;
                }
            }
            return out.build("pdf", truncated);
        } catch (InvalidPasswordException e) {
            throw new ToolRefusal("the PDF is encrypted and has no text this tool can read");
        }
    }

    /** One page's text alone, as {@link #read} extracts it: for checking the positions. */
    static String page(Path file, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setSortByPosition(true);
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc);
        }
    }
}
