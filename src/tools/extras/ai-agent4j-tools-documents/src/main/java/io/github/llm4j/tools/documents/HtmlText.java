package io.github.llm4j.tools.documents;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/**
 * An HTML file's visible text with the JDK's own parser: scripts and styles left out, a line break
 * for each block. HTML has no pages or paragraphs a reader can number, so it reports no parts.
 */
final class HtmlText {

    private static final Set<HTML.Tag> BLOCKS =
            Set.of(HTML.Tag.P, HTML.Tag.DIV, HTML.Tag.BR, HTML.Tag.LI, HTML.Tag.TR, HTML.Tag.H1, HTML.Tag.H2, HTML.Tag.H3,
                    HTML.Tag.H4, HTML.Tag.H5, HTML.Tag.H6, HTML.Tag.TITLE, HTML.Tag.PRE, HTML.Tag.BLOCKQUOTE, HTML.Tag.TABLE);

    private HtmlText() {}

    static Extracted read(String html, int maxChars) throws IOException {
        StringBuilder out = new StringBuilder();
        HTMLEditorKit.ParserCallback collect =
                new HTMLEditorKit.ParserCallback() {
                    int hidden;

                    @Override
                    public void handleStartTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                        if (t == HTML.Tag.SCRIPT || t == HTML.Tag.STYLE) hidden++;
                        else if (BLOCKS.contains(t)) newline();
                    }

                    @Override
                    public void handleEndTag(HTML.Tag t, int pos) {
                        if (t == HTML.Tag.SCRIPT || t == HTML.Tag.STYLE) hidden = Math.max(0, hidden - 1);
                        else if (BLOCKS.contains(t)) newline();
                    }

                    @Override
                    public void handleSimpleTag(HTML.Tag t, MutableAttributeSet a, int pos) {
                        if (BLOCKS.contains(t)) newline();
                    }

                    @Override
                    public void handleText(char[] data, int pos) {
                        if (hidden > 0 || out.length() > maxChars) return;
                        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append(' ');
                        out.append(data);
                    }

                    private void newline() {
                        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                    }
                };
        new ParserDelegator().parse(new StringReader(html), collect, true);
        String text = out.toString().strip();
        return new Extracted("html", text, java.util.List.of(), false);
    }
}
