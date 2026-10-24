package io.github.llm4j.loom.symbols;

import static io.github.llm4j.loom.symbols.SymbolsFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R21 of spec 10: ranges are 0-based lines and UTF-16 columns, whatever the text holds. */
class SymbolsRangesTest {

    @TempDir Path dir;

    private void everyRangeReadsBackAsItsName(String text) throws Exception {
        SymbolIndex i = index(dir, "main.loom", text);
        assertThat(i.problems()).isEmpty();
        assertThat(i.definitions()).isNotEmpty();
        for (SymbolIndex.Definition d : i.definitions())
            assertThat(slice(text, d.range())).as(d.key()).isEqualTo(d.name());
        for (SymbolIndex.Reference r : i.references())
            if (!r.kind().equals("file"))
                assertThat(slice(text, r.range()))
                        .as(r.to())
                        .isEqualTo(r.to().replaceFirst("^[a-z]+:", "").replaceFirst("^.*/", ""));
        for (SymbolIndex.Binding b : i.bindings())
            assertThat(slice(text, b.range())).as(b.key()).isEqualTo(b.name());
    }

    private static final String BODY =
            "agent Writer { model: \"m\" }\n"
                    + "agent Critic { model: \"m\" }\n"
                    + "workflow Main(topic) {\n"
                    + "  delegate \"Write {topic}\" to Writer -> draft\n"
                    + "  delegate \"Review {draft}\" to Critic -> review\n"
                    + "  return review\n"
                    + "}\n";

    @Test
    void line1StartsAtColumnZero() throws Exception {
        SymbolIndex i = index(dir, "main.loom", BODY);
        assertThat(def(i, "agent:Writer").range().line()).isZero();
        assertThat(def(i, "agent:Writer").range().col()).isEqualTo(6);
        everyRangeReadsBackAsItsName(BODY);
    }

    @Test
    void windowsLineEndingsDoNotShiftColumns() throws Exception {
        everyRangeReadsBackAsItsName(BODY.replace("\n", "\r\n"));
        SymbolIndex i = index(dir, "crlf.loom", BODY.replace("\n", "\r\n"));
        assertThat(def(i, "workflow:Main").range().line()).isEqualTo(2);
    }

    @Test
    void tabsCountAsOneColumn() throws Exception {
        everyRangeReadsBackAsItsName(BODY.replace("  ", "\t"));
        SymbolIndex i = index(dir, "tabs.loom", BODY.replace("  ", "\t"));
        assertThat(binding(i, "binding:Main/draft").range().col())
                .isEqualTo(1 + "delegate \"Write {topic}\" to Writer -> ".length());
    }

    @Test
    void anEmojiIsTwoUtf16UnitsSoLaterColumnsMoveByTwo() throws Exception {
        // 🙂 is outside the BMP: two UTF-16 code units, as an editor counts it
        String text =
                "agent Writer { model: \"m\" }\nworkflow Main(topic) {\n"
                        + "  delegate \"🙂🙂 {topic}\" to Writer -> draft\n"
                        + "  note \"{draft}\"\n}\n";
        everyRangeReadsBackAsItsName(text);
        SymbolIndex i = index(dir, "emoji.loom", text);
        SymbolIndex.Binding draft = binding(i, "binding:Main/draft");
        String line = text.split("\n")[draft.range().line()];
        assertThat(line.substring(draft.range().col(), draft.range().endCol())).isEqualTo("draft");
        // the placeholder {topic} after two emoji
        SymbolIndex.Reference use = refs(i, "binding:Main/topic").get(0);
        assertThat(slice(text, use.range())).isEqualTo("topic");
        assertThat(use.range().col()).isEqualTo(line.indexOf("{topic}") + 1);
    }

    @Test
    void aPromptThatSpansLinesKeepsLaterPlacesRight() throws Exception {
        String text =
                "agent Writer { model: \"m\" }\nworkflow Main(topic) {\n"
                        + "  delegate \"First line\nsecond line {topic}\nthird\" to Writer -> draft\n"
                        + "  return draft\n}\n";
        everyRangeReadsBackAsItsName(text);
        SymbolIndex i = index(dir, "multi.loom", text);
        SymbolIndex.Reference use = refs(i, "binding:Main/topic").get(0);
        assertThat(use.range().line()).isEqualTo(3);
        assertThat(slice(text, use.range())).isEqualTo("topic");
        assertThat(binding(i, "binding:Main/draft").range().line()).isEqualTo(4);
    }

    @Test
    void commentsAndBlankLinesAreCounted() throws Exception {
        String text = "// a comment\n\n\n" + BODY;
        everyRangeReadsBackAsItsName(text);
        assertThat(def(index(dir, "c.loom", text), "agent:Writer").range().line()).isEqualTo(3);
    }

    @Test
    void hyphenatedNamesAreOneName() throws Exception {
        String text =
                "agent support-lead { model: \"m\" }\nworkflow W(x) {\n  delegate \"hi\" to support-lead -> r\n}\n";
        everyRangeReadsBackAsItsName(text);
        assertThat(refs(index(dir, "h.loom", text), "agent:support-lead")).hasSize(1);
    }
}
