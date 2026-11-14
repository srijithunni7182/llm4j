package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.tools.openapi.SpecLoader;
import io.github.llm4j.tools.openapi.SpecPin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36a, D12 and G88 (LA35): {@code weave check} compares an openapi tool's {@code pin:} with
 * the operation's signature in the local description, offline; a change to the signature warns, a
 * reworded description does not, and a description it cannot read locally is not guessed at.
 */
class PinChecksTest {

    @TempDir Path dir;

    private static String spec(String idType, String description) {
        return """
                {"openapi":"3.0.3","info":{"title":"t","version":"1"},
                 "paths":{"/things/{id}":{"get":{"operationId":"getThing","summary":"%s",
                   "parameters":[{"name":"id","in":"path","required":true,"description":"%s","schema":{"type":"%s"}}],
                   "responses":{"200":{"description":"ok"}}}}}}
                """
                .formatted(description, description, idType);
    }

    private String pinOf(String specText) throws IOException {
        Path file = dir.resolve("pin-source.json");
        Files.writeString(file, specText);
        return SpecPin.of(
                SpecLoader.load("pin-source.json", dir, SpecLoader.Fetcher.NONE)
                        .operation("getThing")
                        .orElseThrow());
    }

    private List<ScriptValidator.Problem> check(String pin, String spec) throws IOException {
        Files.writeString(dir.resolve("api.json"), spec);
        String source =
                "tool Thing { use: openapi spec: \"api.json\" operation: \"getThing\" base_url: \"https://a.example\" "
                        + (pin == null ? "" : "pin: \"" + pin + "\" ")
                        + "effect: reads }\nworkflow Main() { note \"x\" }";
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        return new ScriptValidator()
                .validate(
                        script,
                        new ScriptValidator.Context().baseDir(dir).registeredTools(java.util.Set.of()));
    }

    private static List<String> pinProblems(List<ScriptValidator.Problem> all) {
        return all.stream().filter(p -> p.message().contains("pin")).map(ScriptValidator.Problem::toString).toList();
    }

    @Test
    void aMatchingPinRaisesNothing() throws Exception {
        String pin = pinOf(spec("integer", "the id"));

        assertThat(pinProblems(check(pin, spec("integer", "the id")))).isEmpty();
    }

    @Test
    void aChangedParameterTypeWarnsAndNamesTheOperation() throws Exception {
        String pin = pinOf(spec("integer", "the id"));

        var found = check(pin, spec("string", "the id"));

        assertThat(pinProblems(found))
                .singleElement()
                .satisfies(
                        text ->
                                assertThat(text)
                                        .contains("getThing")
                                        .contains("LA35")
                                        .contains("weave connect"));
        assertThat(found)
                .filteredOn(p -> p.message().contains("pin"))
                .allSatisfy(p -> assertThat(p.severity()).isEqualTo(ScriptValidator.Severity.WARNING));
    }

    @Test
    void aRewordedDescriptionDoesNotFlipThePin() throws Exception {
        String pin = pinOf(spec("integer", "the id"));

        assertThat(pinProblems(check(pin, spec("integer", "A completely different wording")))).isEmpty();
    }

    @Test
    void noPinIsNotAWarning() throws Exception {
        assertThat(pinProblems(check(null, spec("integer", "x")))).isEmpty();
    }

    @Test
    void aRemoteDescriptionIsNotGuessedAt() throws Exception {
        String source =
                "tool Thing { use: openapi spec: \"https://example.invalid/api.json\" operation: \"getThing\""
                        + " base_url: \"https://a.example\" pin: \"sha256-0123456789abcdef\" effect: reads }\n"
                        + "workflow Main() { note \"x\" }";
        var script = new LoomParser(new Lexer(source).tokenize()).parseScript();

        var found =
                new ScriptValidator()
                        .validate(
                                script,
                                new ScriptValidator.Context()
                                        .baseDir(dir)
                                        .registeredTools(java.util.Set.of()));

        assertThat(pinProblems(found)).isEmpty();
    }

    @Test
    void aPinForAnOperationTheDescriptionLostWarnsToo() throws Exception {
        String pin = pinOf(spec("integer", "x"));
        String without =
                "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{\"/other\":{\"get\":"
                        + "{\"operationId\":\"other\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

        assertThat(pinProblems(check(pin, without)))
                .singleElement()
                .satisfies(text -> assertThat(text).contains("getThing").contains("no longer"));
    }
}
