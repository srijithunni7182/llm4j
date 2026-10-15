package io.github.llm4j.loom.inspect.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R4 (the file): the lines a window catalog is written in, and what is refused. */
class WindowBookTest {

    @Test
    void linesAreReadWithCommentsAndBlankLinesIgnored() {
        WindowBook.Parsed p = WindowBook.parse("# context windows\n\nbig-model = 200000   # usable tokens\nsmall = 8000\n");
        assertThat(p.ok()).isTrue();
        assertThat(p.book().size()).isEqualTo(2);
        assertThat(p.book().usable("big-model")).isEqualTo(200_000L);
        assertThat(p.book().usable("small")).isEqualTo(8_000L);
        assertThat(p.book().usable("nope")).isNull();
    }

    @Test
    void aModelIsFoundWithoutItsProviderPrefixAndExactFirst() {
        WindowBook b = WindowBook.parse("m = 100\nopenai/m = 200").book();
        assertThat(b.usable("openai/m")).isEqualTo(200L);
        assertThat(b.usable("other/m")).isEqualTo(100L);
    }

    @Test
    void badLinesAreRefusedWithTheirLineNumbers() {
        for (String bad : new String[] {"no equals here", "= 5000", "m = abc", "m = 0", "m = -5", "m = 12.5", "m = 99999999999999"}) {
            WindowBook.Parsed p = WindowBook.parse("ok = 1000\n" + bad);
            assertThat(p.ok()).as(bad).isFalse();
            assertThat(p.problems().get(0).line()).as(bad).isEqualTo(2);
        }
    }

    @Test
    void aModelGivenTwiceIsRefused() {
        WindowBook.Parsed p = WindowBook.parse("m = 100\nm = 200");
        assertThat(p.ok()).isFalse();
        assertThat(p.problems().get(0).toString()).contains("twice");
    }

    @Test
    void writeThenParseGivesTheSameEntriesSortedByModel() {
        WindowBook b = WindowBook.parse("zed = 3000\nalpha = 1000").book();
        String text = WindowBook.write(b.entries());
        assertThat(text.indexOf("alpha")).isLessThan(text.indexOf("zed"));
        assertThat(WindowBook.parse(text).book()).isEqualTo(b);
        assertThat(b.entries()).extracting(WindowBook.Entry::model).isEqualTo(List.of("alpha", "zed"));
    }

    @Test
    void anEmptyBookIsEmpty() {
        assertThat(WindowBook.EMPTY.isEmpty()).isTrue();
        assertThat(WindowBook.parse("").book()).isEqualTo(WindowBook.EMPTY);
    }
}
