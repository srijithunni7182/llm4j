package io.github.llm4j.tools.documents;

import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A DOCX's text, paragraph by paragraph, with the JDK alone: the body ({@code word/document.xml})
 * read from the zip with a cap on its unpacked size, parsed with no DTD and no external entities.
 */
final class DocxText {

    static final String BODY = "word/document.xml";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private DocxText() {}

    static Extracted read(Path file, long maxUnpacked, int maxChars) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry body = zip.getEntry(BODY);
            if (body == null) throw new ToolRefusal("the file is not a Word document (it has no " + BODY + ")");
            try (InputStream in = new Capped(zip.getInputStream(body), maxUnpacked)) {
                Paragraphs handler = new Paragraphs(maxChars);
                parser().parse(in, handler);
                return handler.out.build("docx", handler.truncated);
            }
        } catch (java.util.zip.ZipException e) {
            throw new ToolRefusal("the file is not a Word document: " + e.getMessage());
        } catch (SAXException e) {
            if (e.getCause() instanceof ToolRefusal refusal) throw refusal;
            throw new ToolRefusal("the Word document's text could not be read: " + e.getMessage());
        }
    }

    static SAXParser parser() {
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            return f.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("the JDK's XML parser cannot be made safe here", e);
        }
    }

    /** Collects each {@code w:p}'s runs of {@code w:t}, tabs and breaks. */
    private static final class Paragraphs extends DefaultHandler {
        final Extracted.Builder out = new Extracted.Builder("paragraph");
        private final int maxChars;
        private StringBuilder paragraph;
        private boolean inText;
        private int number;
        boolean truncated;

        Paragraphs(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts) {
            if (!W.equals(uri) || truncated) return;
            switch (local) {
                case "p" -> paragraph = new StringBuilder();
                case "t" -> inText = paragraph != null;
                case "tab" -> append("\t");
                case "br", "cr" -> append("\n");
                default -> {}
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            if (!W.equals(uri) || truncated) return;
            if (local.equals("t")) inText = false;
            if (local.equals("p") && paragraph != null) {
                out.add(++number, paragraph.toString());
                paragraph = null;
                if (out.length() > maxChars) truncated = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText) paragraph.append(ch, start, length);
        }

        private void append(String s) {
            if (paragraph != null) paragraph.append(s);
        }
    }

    /** Stops reading past a size: a small zip entry can unpack to gigabytes. */
    private static final class Capped extends java.io.FilterInputStream {
        private final long max;
        private long read;

        Capped(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) {
            read += n;
            if (read > max)
                throw new ToolRefusal("the Word document unpacks to more than " + max + " bytes (max_bytes)");
        }
    }
}
