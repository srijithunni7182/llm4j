package io.github.llm4j.eval.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void equalTextsProduceNoDiff() {
        assertThat(LineDiff.unified("f.txt", "a\nb\n", "a\nb\n")).isEmpty();
        assertThat(LineDiff.unified("f.txt", "", "")).isEmpty();
    }

    @Test
    void hunkHeadersCountLinesIncludingContext() {
        String diff =
                LineDiff.unified(
                        "f.txt", "1\n2\n3\n4\n5\n6\n7\n8\n", "1\n2\n3\nFOUR\n5\n6\n7\n8\n");
        assertThat(diff).contains("@@ -1,7 +1,7 @@").contains("-4\n").contains("+FOUR\n");
        assertThat(diff.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
    }

    @Test
    void farApartChangesBecomeSeparateHunks() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            a.append("line").append(i).append('\n');
            b.append(i == 2 ? "CHANGED2" : i == 29 ? "CHANGED29" : "line" + i).append('\n');
        }
        String diff = LineDiff.unified("f.txt", a.toString(), b.toString());
        assertThat(diff.lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
    }

    @Test
    void additionsToAnEmptyFileAndDeletionsToEmpty() {
        assertThat(LineDiff.unified("f.txt", "", "new\n"))
                .contains("+new\n")
                .contains("@@ -0,0 +1,1 @@");
        assertThat(LineDiff.unified("f.txt", "old\n", ""))
                .contains("-old\n")
                .contains("@@ -1,1 +0,0 @@");
    }
}
