package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** VS.4: a credential-looking value never reaches the graph output. */
class RedactorTest {

    /** Built from pieces so that no credential-shaped string sits in the source (the repository scans for them). */
    static Stream<String> secrets() {
        String tail = "abcdefghijklmnopqrstuvwx";
        return Stream.of(
            "sk" + "-live-" + tail,
            "sk" + "_test_" + tail,
            "xox" + "b-1234567890-abcdefghij",
            "gh" + "p_abcdefghijklmnopqrstuvwxyz0123456789",
            "glp" + "at-abcdefghijklmnopqrst",
            "AK" + "IAABCDEFGHIJKLMNOP",
            "AIza" + "SyA-abcdefghijklmnopqrstuvwxyz012345",
            "Bea" + "rer " + tail + ".yz",
            "ey" + "JhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "-----BEGIN " + "RSA PRIVATE KEY-----");
    }

    @ParameterizedTest
    @MethodSource("secrets")
    void keyShapedTextIsMasked(String secret) {
        String masked = Redactor.mask("use this: " + secret + " now");

        assertThat(masked).doesNotContain(secret).contains(Redactor.MASK).startsWith("use this: ").endsWith(" now");
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        String text = "Write a 500-word post about task-management and sk-ills for {topic}; see https://example.com/a-b";

        assertThat(Redactor.mask(text)).isEqualTo(text);
        assertThat(Redactor.mask(null)).isNull();
    }

    @Test
    void aKeyPastedIntoAPromptDoesNotAppearInTheJsonOrTheMermaid(@TempDir Path dir) throws Exception {
        String secret = "sk" + "-live-abcdefghijklmnopqrstuvwx";
        Path script = Files.writeString(dir.resolve("s.loom"), """
                tool Search { use: serpapi api_key: "%s" }
                agent A { model: "m" system: "token %s" tools: [Search] }
                workflow W() {
                    delegate "call the api with %s" to A -> r
                    note "remember %s"
                    human_prompt "paste Bearer abcdefghijklmnopqrstuvwx" -> x
                }
                """.formatted(secret, secret, secret, secret));

        GraphResult result = new GraphService().graph(script);
        String json = GraphJson.write(result);
        String mermaid = MermaidRenderer.render(result.workflows().get(0));

        assertThat(json).doesNotContain(secret).doesNotContain("abcdefghijklmnopqrstuvwx");
        assertThat(mermaid).doesNotContain(secret);
        assertThat(json).contains(Redactor.MASK);
        assertThat(result.diagnostics()).isEmpty();
    }
}
