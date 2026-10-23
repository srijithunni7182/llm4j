package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.review.fix.UnifiedDiff;
import org.junit.jupiter.api.Test;

/** R28 of spec 09: the diff a dry run prints. */
class UnifiedDiffTest {

    @Test
    void identicalTextsHaveNoDiff() {
        assertThat(UnifiedDiff.of("a", "x\ny\n", "x\ny\n")).isEmpty();
    }

    @Test
    void aChangedLineIsAMinusAndAPlusWithContext() {
        String d = UnifiedDiff.of("f.loom", "one\ntwo\nthree\nfour\n", "one\nTWO\nthree\nfour\n");
        assertThat(d)
                .startsWith("--- a/f.loom\n+++ b/f.loom\n@@ -1,4 +1,4 @@\n")
                .contains("-two\n")
                .contains("+TWO\n")
                .contains(" one\n")
                .contains(" three\n");
    }

    @Test
    void anInsertionAndADeletionAreEachShown() {
        assertThat(UnifiedDiff.of("f", "a\nb\n", "a\nnew\nb\n"))
                .contains("+new\n")
                .doesNotContain("-b");
        assertThat(UnifiedDiff.of("f", "a\nb\nc\n", "a\nc\n")).contains("-b\n");
    }

    @Test
    void farApartChangesMakeSeparateHunks() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            a.append("line").append(i).append('\n');
            b.append(i == 2 ? "CHANGED2" : i == 29 ? "CHANGED29" : "line" + i).append('\n');
        }
        String d = UnifiedDiff.of("f", a.toString(), b.toString());
        assertThat(d.split("\n@@ ", -1).length - 1).isEqualTo(2); // two hunks
        assertThat(d).contains("+CHANGED2\n").contains("+CHANGED29\n");
    }
}
