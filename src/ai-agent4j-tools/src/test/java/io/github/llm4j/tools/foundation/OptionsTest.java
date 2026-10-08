package io.github.llm4j.tools.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.support.Fuzz;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OptionsTest {

    private static Options of(String k, String v) {
        return new Options(Map.of(k, v));
    }

    @ParameterizedTest
    @CsvSource({"500ms,PT0.5S", "20s,PT20S", "2m,PT2M", "1h,PT1H", "30,PT30S", "  45 s ,PT45S"})
    @Tag("V1.2")
    void durationsInTheFormsTheGuideShows(String text, String expected) {
        assertThat(of("timeout", text).duration("timeout", Duration.ofSeconds(1), Duration.ofHours(2)))
                .isEqualTo(Duration.parse(expected));
    }

    @ParameterizedTest
    @CsvSource({"64k,65536", "1m,1048576", "512,512", "2KB,2048", "3MB,3145728"})
    @Tag("V1.2")
    void sizesInTheFormsTheGuideShows(String text, long bytes) {
        assertThat(of("max_bytes", text).size("max_bytes", 1, Long.MAX_VALUE)).isEqualTo(bytes);
    }

    @Test
    @Tag("V1.2")
    void badValuesNameTheOption() {
        assertThatThrownBy(() -> of("timeout", "soon").duration("timeout", Duration.ofSeconds(1), Duration.ofMinutes(1)))
                .isInstanceOf(OptionException.class).hasMessageContaining("timeout").hasMessageContaining("soon");
        assertThatThrownBy(() -> of("timeout", "11m").duration("timeout", Duration.ofSeconds(1), Duration.ofMinutes(10)))
                .hasMessageContaining("at most 10m");
        assertThatThrownBy(() -> of("max_rows", "0").integer("max_rows", 100, 1, 1000)).hasMessageContaining("between 1 and 1000");
        assertThatThrownBy(() -> of("max_rows", "x").integer("max_rows", 100, 1, 1000)).hasMessageContaining("whole number");
        assertThatThrownBy(() -> of("flag", "yes").bool("flag", false)).hasMessageContaining("true or false");
        assertThatThrownBy(() -> of("format", "xml").choice("format", "json", "json", "csv")).hasMessageContaining("one of json, csv");
        assertThatThrownBy(() -> new Options(Map.of()).require("url")).hasMessageContaining("url: is required");
    }

    @Test
    @Tag("V1.2")
    void listsAreTrimmedAndDropEmptyEntries() {
        assertThat(of("hosts", " a.com, b.com ,, *.c.com ").list("hosts")).containsExactly("a.com", "b.com", "*.c.com");
        assertThat(new Options(Map.of()).list("hosts")).isEmpty();
    }

    @Test
    @Tag("V1.4")
    void headerOptionsAreCollectedByName() {
        Options o = new Options(Map.of("header.Accept", "application/json", "header.X-Trace-Id", "abc", "other", "x"));
        assertThat(o.headers()).containsOnly(Map.entry("Accept", "application/json"), Map.entry("X-Trace-Id", "abc"));
    }

    @ParameterizedTest
    @CsvSource({"Authorization,true", "X-Api-Key,true", "X-Auth-Token,true", "Cookie,true", "X-Secret,true", "Proxy-Password,true",
            "Accept,false", "X-Trace-Id,false", "Content-Type,false"})
    @Tag("V1.4")
    void credentialLookingHeadersAreSecrets(String name, boolean secret) {
        assertThat(Options.isSecretHeader(name)).isEqualTo(secret);
    }

    @Test
    @Tag("F5")
    void generatedDurationsAndSizesRoundTripOrFailCleanly() {
        Fuzz.run("options", random -> {
            long n = random.nextInt(100000);
            String[] units = {"ms", "s", "m", "h", ""};
            String unit = units[random.nextInt(units.length)];
            Duration expected = switch (unit) {
                case "ms" -> Duration.ofMillis(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofSeconds(n);
            };
            assertThat(Options.parseDuration(n + unit)).isEqualTo(expected);

            String[] sizeUnits = {"b", "k", "kb", "m", "mb", ""};
            String su = sizeUnits[random.nextInt(sizeUnits.length)];
            long factor = su.startsWith("k") ? 1024 : su.startsWith("m") ? 1024 * 1024 : 1;
            assertThat(Options.parseSize(n + su)).isEqualTo(n * factor);

            String junk = junk(random);
            // Malformed input is reported (null / negative), never thrown.
            Options.parseDuration(junk);
            Options.parseSize(junk);
            Options o = new Options(Map.of("x", junk));
            try {
                o.duration("x", Duration.ofSeconds(1), Duration.ofHours(1));
                o.size("x", 1, 1 << 30);
            } catch (OptionException expectedOnJunk) {
                // fine: a load error
            }
        });
    }

    private static String junk(Random random) {
        String alphabet = "0123456789 .,-+smhkbMBKx/é٠";
        StringBuilder sb = new StringBuilder();
        for (int i = random.nextInt(8); i >= 0; i--) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }
}
