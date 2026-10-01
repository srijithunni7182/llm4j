package io.github.llm4j.loom.generic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.tools.generic.Limits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    @Tag("V1.7")
    void cutKeepsWholeCharactersAndSaysWhatWentMissing() {
        String text = "é".repeat(100); // 2 bytes each
        String cut = Limits.cut(text, 51);
        assertThat(cut).startsWith("é".repeat(25)).contains("[cut: 50 of 200 bytes shown]");
        assertThat(Limits.cut("short", 100)).isEqualTo("short");
    }

    @Test
    @Tag("V3.7")
    void readCappedReadsOnlyWhatItMayAndKnowsThereWasMore() throws IOException {
        Limits.Capped big = Limits.readCapped(new ByteArrayInputStream(new byte[10_000]), 100);
        assertThat(big.bytes()).hasSize(100);
        assertThat(big.truncated()).isTrue();

        Limits.Capped exact = Limits.readCapped(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 5);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.text()).isEqualTo("12345");
    }

    @Test
    @Tag("V3.7")
    void readCappedDoesNotReadTheWholeStream() throws IOException {
        // A very large (here: 5 MB, so a failing reader fails the test rather than exhausting memory) stream.
        long[] produced = {0};
        InputStream huge = new InputStream() {
            @Override public int read() { return produced[0]++ < 5_000_000 ? 'x' : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (produced[0] >= 5_000_000) return -1;
                int n = (int) Math.min(len, 5_000_000 - produced[0]);
                java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                produced[0] += n;
                return n;
            }
        };
        Limits.Capped c = Limits.readCapped(huge, 1000);
        assertThat(c.truncated()).isTrue();
        assertThat(c.bytes()).hasSize(1000);
        assertThat(produced[0]).as("bytes pulled from the stream").isLessThan(20_000);
    }

    @Test
    void excerptIsOneShortLine() {
        assertThat(Limits.excerpt("a\n b\t c", 50)).isEqualTo("a b c");
        assertThat(Limits.excerpt("x".repeat(300), 10)).isEqualTo("xxxxxxxxxx…");
    }
}
