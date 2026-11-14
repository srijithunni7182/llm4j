package io.github.llm4j.tools;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads a JUnit XML report into a {@link Verdict}, counting test by test (a report's own {@code
 * tests=} attributes are not trusted). A document type declaration is refused outright, so no
 * entity, external or internal, is ever read.
 */
final class JUnitReport {

    private JUnitReport() {}

    static Verdict read(String xml, String source, int maxItems) {
        Document doc;
        try {
            doc = parser().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Verdict.unreadable(
                    "junit",
                    source,
                    maxItems,
                    "the report is not well-formed JUnit XML: " + reason(e));
        }
        Element root = doc.getDocumentElement();
        if (!root.getTagName().equals("testsuites") && !root.getTagName().equals("testsuite"))
            return Verdict.unreadable(
                    "junit",
                    source,
                    maxItems,
                    "the report's root is <"
                            + root.getTagName()
                            + ">, not <testsuites> or <testsuite>");
        Verdict v = new Verdict("junit", source, maxItems);
        NodeList cases = root.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element c = (Element) cases.item(i);
            String cls = c.getAttribute("classname");
            String name =
                    cls.isEmpty() ? c.getAttribute("name") : cls + "." + c.getAttribute("name");
            Element failure = child(c, "failure");
            Element error = child(c, "error");
            if (failure != null) v.failed(name, message(failure));
            else if (error != null) v.error(name, message(error));
            else if (child(c, "skipped") != null) v.skipped(name);
            else v.passed(name);
        }
        return v;
    }

    private static final org.xml.sax.ErrorHandler SILENT =
            new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException e) {}

                @Override
                public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e)
                        throws org.xml.sax.SAXException {
                    throw e;
                }
            };

    private static DocumentBuilder parser() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // No DOCTYPE at all: with none, there is no entity to resolve, external or internal (the
        // OWASP recommendation, stronger than turning entities off one by one)
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder b = f.newDocumentBuilder();
        // the default handler prints a malformed report's errors to the console; this one only
        // throws, and the caller turns the exception into the verdict
        b.setErrorHandler(SILENT);
        return b;
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        return null;
    }

    private static String message(Element e) {
        String m = e.getAttribute("message");
        return m.isEmpty() ? e.getTextContent().strip() : m;
    }

    private static String reason(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
