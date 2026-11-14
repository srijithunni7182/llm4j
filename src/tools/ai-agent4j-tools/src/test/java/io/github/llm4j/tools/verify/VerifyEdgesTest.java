package io.github.llm4j.tools.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.VerifyKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The exact edges of {@code verify}: each schema keyword at its limit, messages, sizes. */
class VerifyEdgesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    JsonNode call(Map<String, String> options, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out =
                new VerifyKind().create("Check", options, dir, new RecordingEffects()).execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    JsonNode schema(String schema, String document) throws Exception {
        return call(Map.of(), "format", "schema", "schema_text", schema, "document", document);
    }

    List<String> messages(String schema, String document) throws Exception {
        return schema(schema, document).get("items").findValuesAsText("message");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{\"minimum\": 5} | 5 | 0",
                "{\"minimum\": 5} | 4.99 | 1",
                "{\"maximum\": 5} | 5 | 0",
                "{\"maximum\": 5} | 5.01 | 1",
                "{\"exclusiveMinimum\": 5} | 5 | 1",
                "{\"exclusiveMinimum\": 5} | 5.01 | 0",
                "{\"exclusiveMaximum\": 5} | 5 | 1",
                "{\"exclusiveMaximum\": 5} | 4.99 | 0",
                "{\"minimum\": \"5\"} | 1 | 0",
                "{\"minLength\": 3} | \"abc\" | 0",
                "{\"minLength\": 3} | \"ab\" | 1",
                "{\"maxLength\": 3} | \"abc\" | 0",
                "{\"maxLength\": 3} | \"abcd\" | 1",
                "{\"maxLength\": 2} | \"é😀\" | 0",
                "{\"minItems\": 2} | [1,2] | 0",
                "{\"maxItems\": 2} | [1,2,3] | 1",
                "{\"type\": \"integer\"} | 2.0 | 0",
                "{\"type\": \"integer\"} | 2.5 | 1",
                "{\"enum\": [1, \"a\"]} | 1.0 | 0",
                "{\"enum\": [1, \"a\"]} | \"a\" | 0",
                "{\"enum\": [1, \"a\"]} | \"b\" | 1",
                "{\"const\": 2} | 2.00 | 0",
                "{\"const\": {\"a\": 1}} | {\"a\": 1} | 0",
                "{\"const\": {\"a\": 1}} | {\"a\": 2} | 1",
                "false | 1 | 1",
                "true | 1 | 0",
                "{\"additionalProperties\": {\"type\": \"number\"}} | {\"x\": \"s\"} | 1",
                "{\"additionalProperties\": {\"type\": \"number\"}} | {\"x\": 1} | 0",
                "{\"additionalProperties\": true} | {\"x\": 1} | 0",
                "{\"properties\": {\"a\": false}} | {\"a\": 1} | 1"
            })
    void eachKeywordHoldsAtItsLimit(String schema, String document, int violations)
            throws Exception {
        JsonNode r = schema(schema, document);
        assertThat(r.get("failed").asInt()).as(r.toString()).isEqualTo(violations);
        assertThat(r.get("passed").asInt()).isEqualTo(violations == 0 ? 1 : 0);
        assertThat(r.get("total").asInt()).isEqualTo(1);
    }

    @Test
    void typeMessagesSayWhatWasExpectedAndWhatWasFound() throws Exception {
        String schema = "{\"type\": [\"string\", \"null\"]}";
        assertThat(messages(schema, "1")).containsExactly("must be string or null, not a number");
        assertThat(messages(schema, "true"))
                .containsExactly("must be string or null, not true or false");
        assertThat(messages(schema, "{}")).containsExactly("must be string or null, not an object");
        assertThat(messages(schema, "[]")).containsExactly("must be string or null, not an array");
        assertThat(messages("{\"type\": \"number\"}", "\"x\""))
                .containsExactly("must be number, not a string");
        assertThat(messages("{\"type\": \"string\"}", "null"))
                .containsExactly("must be string, not null");
        assertThat(messages("{\"type\": \"thing\"}", "1"))
                .containsExactly("must be thing, not a number");
        assertThat(messages("{\"minimum\": 5}", "1")).containsExactly("must be at least 5");
        assertThat(messages("{\"maximum\": 5}", "9")).containsExactly("must be at most 5");
        assertThat(messages("{\"exclusiveMinimum\": 5}", "1"))
                .containsExactly("must be more than 5");
        assertThat(messages("{\"exclusiveMaximum\": 5}", "9"))
                .containsExactly("must be less than 5");
        assertThat(messages("{\"minItems\": 2}", "[]"))
                .containsExactly("must have at least 2 items");
        assertThat(messages("{\"maxLength\": 1}", "\"ab\""))
                .containsExactly("must have at most 1 characters");
        assertThat(messages("{\"const\": 2}", "3")).containsExactly("must be 2");
        assertThat(messages("{\"enum\": [1]}", "3")).containsExactly("must be one of [1]");
        assertThat(messages("false", "3")).containsExactly("no value is allowed here");
        assertThat(schema("false", "3").get("items").get(0).get("name").asText()).isEqualTo("/");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[1] | the schema is not an object",
                "{\"properties\": []} | properties is not an object",
                "{\"properties\": {\"a\": 1}} | the schema at /properties/a is not an object",
                "{\"properties\": {\"a\": {\"pattern\": \"x\"}}} | the keyword pattern at /properties/a is not supported",
                "{\"items\": {\"oneOf\": []}} | the keyword oneOf at /items is not supported",
                "{\"additionalProperties\": {\"$ref\": \"#\"}} | the keyword $ref at /additionalProperties is not supported",
                "{\"format\": \"date\"} | the keyword format is not supported"
            })
    void aSchemaItCannotReadIsNamedExactly(String schema, String problem) throws Exception {
        assertThat(schema(schema, "1").get("error").asText()).startsWith(problem);
    }

    @Test
    void theSchemaDepthLimitIsExact() throws Exception {
        String at = "{\"items\":".repeat(63) + "{}" + "}".repeat(63);
        assertThat(schema(at, "[]").get("status").asText()).isEqualTo("passed");
        String past = "{\"items\":".repeat(65) + "true" + "}".repeat(65);
        assertThat(schema(past, "[]").get("error").asText()).contains("nested more than 64 deep");
        String deepDoc = "[".repeat(70) + "]".repeat(70);
        assertThat(schema("true", deepDoc).get("error").asText())
                .isEqualTo("the document is nested more than 64 deep");
        assertThat(schema("{}", "").get("error").asText()).isEqualTo("the document is empty");
    }

    @Test
    void messagesAreCutAtExactlyFiveHundredCharacters() throws Exception {
        String at = "x".repeat(500);
        JsonNode r =
                call(
                        Map.of(),
                        "format",
                        "junit",
                        "report",
                        "<testsuite><testcase name=\"a\"><failure message=\""
                                + at
                                + "\"/></testcase>"
                                + "<testcase name=\"b\"><failure message=\""
                                + at
                                + "y\"/></testcase></testsuite>");
        assertThat(r.get("items").get(0).get("message").asText()).isEqualTo(at);
        assertThat(r.get("items").get(1).get("message").asText()).isEqualTo(at + "…");
        assertThat(r.get("items").get(0).get("name").asText()).isEqualTo("a");
    }

    @Test
    void everyTestIsAnItemWithItsStatus() throws Exception {
        JsonNode r =
                call(
                        Map.of(),
                        "format",
                        "junit",
                        "report",
                        "<testsuite><testcase name=\"p\"/><testcase name=\"e\"><error>boom</error></testcase>"
                                + "<testcase name=\"s\"><skipped/></testcase></testsuite>");
        assertThat(r.get("items").findValuesAsText("status"))
                .containsExactly("passed", "error", "skipped");
        assertThat(r.get("items").get(1).get("message").asText()).isEqualTo("boom");
        assertThat(r.get("items").get(0).has("message")).isFalse();
    }

    @Test
    void aMalformedReportPrintsNothingToTheConsoleAndSaysWhy() throws Exception {
        PrintStream before = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true));
        try {
            JsonNode r = call(Map.of(), "format", "junit", "report", "<testsuite><testcase>");
            assertThat(r.get("error").asText())
                    .startsWith("the report is not well-formed JUnit XML: ")
                    .hasSizeGreaterThan(45);
        } finally {
            System.setErr(before);
        }
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void filesAreBoundedExactlyAndOnlyFilesAreRead() throws Exception {
        Files.createDirectories(dir.resolve("out/sub"));
        String report = "<testsuite/>";
        Files.writeString(
                dir.resolve("out/exact.xml"), report + " ".repeat(1024 - report.length()));
        Files.writeString(dir.resolve("out/over.xml"), report + " ".repeat(1025 - report.length()));
        Map<String, String> o = Map.of("root", "out", "max_bytes", "1k");
        assertThat(call(o, "format", "junit", "source", "exact.xml").get("status").asText())
                .isEqualTo("empty");
        assertThat(call(o, "format", "junit", "source", "over.xml").asText())
                .isEqualTo("Error: over.xml is larger than 1024 bytes (max_bytes)");
        assertThat(call(o, "format", "junit", "source", "sub").asText())
                .isEqualTo("Error: sub is not a file under the tool's root");
        assertThat(
                        call(
                                        o,
                                        "format",
                                        "junit",
                                        "report",
                                        report + " ".repeat(1024 - report.length()))
                                .get("total")
                                .asInt())
                .isZero();
        assertThat(
                        call(
                                        o,
                                        "format",
                                        "junit",
                                        "report",
                                        report + " ".repeat(1025 - report.length()))
                                .asText())
                .isEqualTo("Error: report is larger than 1024 bytes (max_bytes)");
    }

    @Test
    void theDescriptionOffersFilesOnlyWhenThereIsARoot() throws Exception {
        String without =
                new VerifyKind()
                        .create("C", Map.of(), dir, new RecordingEffects())
                        .getDescription();
        String with =
                new VerifyKind()
                        .create("C", Map.of("root", "out"), dir, new RecordingEffects())
                        .getDescription();
        assertThat(without).doesNotContain("source").doesNotContain("schema: a file");
        assertThat(with).contains("or source: a file").contains("or schema: a file");
        assertThat(call(Map.of(), "format", "schema", "schema", "s.json", "document", "1").asText())
                .contains("declare root:");
        assertThat(call(Map.of(), "format", "schema", "document", "1").asText())
                .isEqualTo("Error: a schema check needs schema_text");
        assertThat(call(Map.of("root", "out"), "format", "schema", "document", "1").asText())
                .isEqualTo("Error: a schema check needs schema_text or schema (a file)");
        assertThat(call(Map.of(), "format", "junit", "report", 5).asText())
                .isEqualTo("Error: report is required");
    }

    @Test
    void evenAnInternalEntityIsRefused() throws Exception {
        JsonNode r =
                call(
                        Map.of(),
                        "format",
                        "junit",
                        "report",
                        "<!DOCTYPE t [<!ENTITY x \"expanded-canary\">]><testsuite><testcase name=\"&x;\"/></testsuite>");
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.toString()).doesNotContain("expanded-canary");
        assertThat(r.get("error").asText()).contains("DOCTYPE");
    }
}
