package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextWindowsTest {

    @TempDir
    Path dir;

    @Test
    void noWindowsShipWithTheLibrary() {
        assertThat(ContextWindows.empty().window("anything")).isEmpty();
        assertThat(getClass().getClassLoader().getResource("io/github/llm4j/context/windows.properties")).isNull();
    }

    @Test
    void loadsTheDevelopersOwnWindows() throws Exception {
        Path f = dir.resolve("windows.properties");
        Files.writeString(f, "# my models\ntest/big = 200000 / 8192\ntest/plain = 32000   # no output reserve\n\n");
        ContextWindows w = ContextWindows.load(f);
        assertThat(w.window("test/big")).hasValue(ContextWindow.of(200_000).reservingForOutput(8_192));
        assertThat(w.window("test/plain")).hasValue(ContextWindow.of(32_000));
        assertThat(w.window("test/unknown")).isEmpty();
    }

    @Test
    void lookupIgnoresCaseAndTheProviderPrefix() throws Exception {
        Path f = dir.resolve("w.properties");
        Files.writeString(f, "gpt-test = 1000\n");
        ContextWindows w = ContextWindows.load(f);
        assertThat(w.window("openai/gpt-test")).isPresent();
        assertThat(w.window("GPT-TEST")).isPresent();
        assertThat(w.window(null)).isEmpty();
    }

    @Test
    void anExactEntryBeatsTheOneWithoutAPrefix() throws Exception {
        Path f = dir.resolve("w.properties");
        Files.writeString(f, "gpt-test = 1000\nacme/gpt-test = 2000\n");
        assertThat(ContextWindows.load(f).window("acme/gpt-test").orElseThrow().tokens()).isEqualTo(2_000);
        assertThat(ContextWindows.load(f).window("other/gpt-test").orElseThrow().tokens()).isEqualTo(1_000);
    }

    @Test
    void withAddsOrOverridesWithoutChangingTheOriginal() {
        ContextWindows a = ContextWindows.empty().with("m", ContextWindow.of(1_000));
        ContextWindows b = a.with("m", ContextWindow.of(2_000));
        assertThat(a.window("m").orElseThrow().tokens()).isEqualTo(1_000);
        assertThat(b.window("m").orElseThrow().tokens()).isEqualTo(2_000);
        assertThat(ContextWindows.empty().window("m")).isEmpty();
    }

    @Test
    void badLinesAreReportedWithTheirLineNumber() throws Exception {
        assertBad("ok = 1000\nnonsense\n", ":2:");
        assertBad("m = abc\n", ":1:");
        assertBad("m = -5\n", ":1:");
        assertBad("m = 0\n", ":1:");
        assertBad("m = 1000 / 1000\n", ":1:");
        assertBad("m = 1000 / x\n", ":1:");
        assertBad("m = 1 / 2 / 3\n", ":1:");
        assertBad("= 1000\n", ":1:");
        assertBad("m = 1000\nm = 2000\n", ":2:");
    }

    @Test
    void aMissingFileIsAnError() {
        assertThatThrownBy(() -> ContextWindows.load(dir.resolve("nope"))).isInstanceOf(java.io.IOException.class);
    }

    private void assertBad(String text, String where) throws Exception {
        Path f = dir.resolve("bad.properties");
        Files.writeString(f, text);
        assertThatThrownBy(() -> ContextWindows.load(f)).as(text).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(where);
    }
}
