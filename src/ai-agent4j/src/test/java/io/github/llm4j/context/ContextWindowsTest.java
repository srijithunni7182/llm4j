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
    void anEmptyCatalogueKnowsNothingAndIsWhatYouGetUnlessYouAskForTheSeed() {
        assertThat(ContextWindows.empty().window("claude-opus-5-5")).isEmpty();
        assertThat(ContextWindows.empty().window("anything")).isEmpty();
    }

    @Test
    void theSeedHoldsOnlyModelsCheckedAgainstTheProvidersDocumentation() throws Exception {
        ContextWindows seed = ContextWindows.defaults();
        for (String model : new String[] {"claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"}) {
            ContextWindow w = seed.window(model).orElseThrow(() -> new AssertionError(model + " is missing from the seed"));
            assertThat(w.tokens()).as(model).isEqualTo(1_000_000);
            assertThat(w.reservedForOutput()).as(model).isEqualTo(128_000);
            assertThat(w.usableInput()).as(model).isEqualTo(872_000);
        }
        assertThat(seed.window("anthropic/claude-opus-5-5")).isPresent(); // with a provider prefix, as the library names models elsewhere
        assertThat(seed.window("gemini-2.5-flash")).as("not seeded until checked against Google's documentation").isEmpty();
        assertThat(seed.window("some-model-nobody-checked")).isEmpty();
    }

    @Test
    void theSeedFileSaysWhenAndAgainstWhatEachEntryWasChecked() throws Exception {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("io/github/llm4j/context/windows.properties")) {
            assertThat(in).isNotNull();
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).contains("Checked against https://platform.claude.com/docs/en/about-claude/models/overview on 2026-10-14");
            long entries = text.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).count();
            assertThat(entries).isEqualTo(4);
        }
    }

    @Test
    void theSeedCanBeExtendedAndOverriddenWithoutChangingIt() {
        ContextWindows mine = ContextWindows.defaults().with("claude-opus-5-5", ContextWindow.of(200_000)).with("acme/x", ContextWindow.of(32_000));
        assertThat(mine.window("claude-opus-5-5").orElseThrow().tokens()).isEqualTo(200_000);
        assertThat(mine.window("acme/x")).isPresent();
        assertThat(ContextWindows.defaults().window("claude-opus-5-5").orElseThrow().tokens()).isEqualTo(1_000_000);
        assertThat(ContextWindows.defaults().window("acme/x")).isEmpty();
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
