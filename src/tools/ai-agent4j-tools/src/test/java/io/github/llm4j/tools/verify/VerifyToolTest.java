package io.github.llm4j.tools.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.VerifyKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D12 and G61: a verifier's result becomes one typed record; a parse failure is a failed
 * check, an empty report is {@code total: 0}, and the text inside it stays unchecked.
 */
class VerifyToolTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    JsonNode call(Map<String, String> options, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out =
                new VerifyKind().create("Check", options, dir, new RecordingEffects()).execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    JsonNode call(Object... args) throws Exception {
        return call(Map.of(), args);
    }

    static final String REPORT =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="Orders" tests="4">
                <testcase classname="OrdersTest" name="addsALine"/>
                <testcase classname="OrdersTest" name="refusesANegativeQuantity">
                  <failure message="expected 400 but was 200">stack</failure>
                </testcase>
                <testcase classname="OrdersTest" name="talksToTheDatabase">
                  <error message="connection refused"/>
                </testcase>
                <testcase classname="OrdersTest" name="laterFeature"><skipped/></testcase>
              </testsuite>
            </testsuites>
            """;

    // ---- the kind

    @Test
    void withoutARootItIsPureAndWithOneItReadsFiles() {
        ToolProfile pure = new VerifyKind().profile(Map.of());
        assertThat(pure.untrusted() || pure.privateData() || pure.outward()).isFalse();
        assertThat(pure.effect()).isEqualTo(TaskEffect.NONE);
        ToolProfile reads = new VerifyKind().profile(Map.of("root", "out"));
        assertThat(reads.privateData()).isTrue();
        assertThat(reads.outward() || reads.untrusted()).isFalse();
        assertThat(reads.effect()).isEqualTo(TaskEffect.READS);
        assertThat(reads.note()).contains("out");
    }

    @Test
    void namesAndMessagesAreMarkedAsTextAModelMayHaveWritten() {
        assertThat(new VerifyKind().uncheckedResult(Map.of()))
                .containsExactlyInAnyOrder("items", "error");
    }

    @Test
    void itsOptionsAreChecked() {
        assertThat(new VerifyKind().check(Map.of("max_items", "0"), dir)).contains("max_items");
        assertThat(new VerifyKind().check(Map.of("root", "../elsewhere"), dir)).contains("root");
        assertThat(new VerifyKind().check(Map.of("root", "out", "max_bytes", "2m"), dir)).isNull();
    }

    // ---- JUnit XML

    @Test
    void aJUnitReportIsCountedTestByTest() throws Exception {
        JsonNode r = call("format", "junit", "report", REPORT);
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.get("total").asInt()).isEqualTo(4);
        assertThat(r.get("passed").asInt()).isEqualTo(1);
        // failed counts every test that neither passed nor was skipped; errors are among them
        assertThat(r.get("failed").asInt()).isEqualTo(2);
        assertThat(r.get("errors").asInt()).isEqualTo(1);
        assertThat(r.get("skipped").asInt()).isEqualTo(1);
        assertThat(r.get("items").get(1).get("name").asText())
                .isEqualTo("OrdersTest.refusesANegativeQuantity");
        assertThat(r.get("items").get(1).get("status").asText()).isEqualTo("failed");
        assertThat(r.get("items").get(1).get("message").asText())
                .isEqualTo("expected 400 but was 200");
        assertThat(r.get("items_truncated").asBoolean()).isFalse();
        assertThat(r.get("format").asText()).isEqualTo("junit");
    }

    @Test
    void allPassingIsPassed() throws Exception {
        JsonNode r =
                call(
                        "format",
                        "junit",
                        "report",
                        "<testsuite><testcase classname=\"A\" name=\"b\"/></testsuite>");
        assertThat(r.get("status").asText()).isEqualTo("passed");
        assertThat(r.get("passed").asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyReportIsTotalZeroAndNotAPass() throws Exception {
        JsonNode r = call("format", "junit", "report", "<testsuites/>");
        assertThat(r.get("total").asInt()).isZero();
        assertThat(r.get("status").asText()).isEqualTo("empty");
    }

    @Test
    void aReportThatCannotBeReadIsAFailedCheckNeverAPass() throws Exception {
        for (String bad :
                new String[] {
                    "<testsuite><testcase", "not xml at all", "<html><body/></html>", ""
                }) {
            JsonNode r = call("format", "junit", "report", bad);
            assertThat(r.get("status").asText()).as(bad).isEqualTo("failed");
            assertThat(r.get("total").asInt()).isZero();
            assertThat(r.get("failed").asInt())
                    .as("a gate on failed > 0 must not pass")
                    .isEqualTo(1);
            assertThat(r.get("error").asText()).isNotBlank();
        }
    }

    @Test
    void aReportWithADoctypeIsRefusedSoNoEntityIsRead() throws Exception {
        Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-CANARY");
        String xxe =
                "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file://"
                        + dir.resolve("secret.txt")
                        + "\">]><testsuite><testcase classname=\"&x;\" name=\"n\"/></testsuite>";
        JsonNode r = call("format", "junit", "report", xxe);
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.toString()).doesNotContain("TOP-SECRET-CANARY");
    }

    @Test
    void aReportIsReadFromAFileUnderTheRoot() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("out/report.xml"), REPORT);
        JsonNode r = call(Map.of("root", "out"), "format", "junit", "source", "report.xml");
        assertThat(r.get("total").asInt()).isEqualTo(4);
        assertThat(r.get("source").asText()).isEqualTo("report.xml");
    }

    @Test
    void aFileOutsideTheRootOrWithoutARootIsRefused() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("other.xml"), REPORT);
        assertThat(
                        call(Map.of("root", "out"), "format", "junit", "source", "../other.xml")
                                .asText())
                .startsWith("Error:")
                .contains("outside");
        assertThat(call("format", "junit", "source", "other.xml").asText())
                .startsWith("Error:")
                .contains("root");
        assertThat(call(Map.of("root", "out"), "format", "junit", "source", "missing.xml").asText())
                .startsWith("Error:")
                .contains("missing.xml");
    }

    @Test
    void itIsBounded() throws Exception {
        StringBuilder many = new StringBuilder("<testsuite>");
        for (int i = 0; i < 30; i++)
            many.append("<testcase classname=\"A\" name=\"t").append(i).append("\"/>");
        many.append("</testsuite>");
        JsonNode r = call(Map.of("max_items", "10"), "format", "junit", "report", many.toString());
        assertThat(r.get("total").asInt()).isEqualTo(30);
        assertThat(r.get("items").size()).isEqualTo(10);
        assertThat(r.get("items_truncated").asBoolean()).isTrue();

        JsonNode big =
                call(Map.of("max_bytes", "1k"), "format", "junit", "report", many.toString());
        assertThat(big.asText()).startsWith("Error:").contains("larger than");

        String longMessage = "x".repeat(5000);
        JsonNode cut =
                call(
                        "format",
                        "junit",
                        "report",
                        "<testsuite><testcase classname=\"A\" name=\"b\"><failure message=\""
                                + longMessage
                                + "\"/></testcase></testsuite>");
        assertThat(cut.get("items").get(0).get("message").asText().length()).isLessThan(600);
    }

    @Test
    void theFormatMustBeOneItKnows() throws Exception {
        assertThat(call("format", "tap", "report", "ok 1").asText())
                .startsWith("Error:")
                .contains("junit")
                .contains("schema");
        assertThat(call("format", "junit").asText()).startsWith("Error:").contains("report");
    }

    // ---- JSON Schema

    static final String SCHEMA =
            """
            {"$schema": "https://json-schema.org/draft/2020-12/schema", "title": "Invoice",
             "type": "object", "required": ["id", "total", "lines"], "additionalProperties": false,
             "properties": {
               "id": {"type": "string", "minLength": 3, "maxLength": 10},
               "total": {"type": "number", "minimum": 0, "exclusiveMaximum": 10000},
               "currency": {"enum": ["EUR", "USD"]},
               "paid": {"type": "boolean"},
               "kind": {"const": "invoice"},
               "lines": {"type": "array", "minItems": 1, "maxItems": 3,
                         "items": {"type": "object", "required": ["qty"],
                                   "properties": {"qty": {"type": "integer", "maximum": 99}}}},
               "note": {"type": ["string", "null"]}}}
            """;

    @Test
    void aDocumentThatFitsTheSchemaPasses() throws Exception {
        JsonNode r =
                call(
                        "format",
                        "schema",
                        "schema_text",
                        SCHEMA,
                        "document",
                        "{\"id\":\"INV-1\",\"total\":12.5,\"currency\":\"EUR\",\"paid\":true,"
                                + "\"kind\":\"invoice\",\"lines\":[{\"qty\":2}],\"note\":null}");
        assertThat(r.get("status").asText()).as(r.toString()).isEqualTo("passed");
        assertThat(r.get("failed").asInt()).isZero();
        assertThat(r.get("format").asText()).isEqualTo("schema");
    }

    @Test
    void everyPlaceADocumentDoesNotFitIsListed() throws Exception {
        JsonNode r =
                call(
                        "format",
                        "schema",
                        "schema_text",
                        SCHEMA,
                        "document",
                        "{\"id\":\"X\",\"total\":-1,\"currency\":\"GBP\",\"paid\":\"yes\","
                                + "\"kind\":\"memo\",\"lines\":[{\"qty\":2.5},{}],\"extra\":1}");
        assertThat(r.get("status").asText()).isEqualTo("failed");
        String names = r.get("items").findValuesAsText("name").toString();
        assertThat(names)
                .contains(
                        "/id",
                        "/total",
                        "/currency",
                        "/paid",
                        "/kind",
                        "/lines/0/qty",
                        "/lines/1",
                        "/extra");
        assertThat(r.get("failed").asInt()).isEqualTo(r.get("items").size());
        assertThat(
                        call("format", "schema", "schema_text", SCHEMA, "document", "{\"total\":1}")
                                .get("items")
                                .findValuesAsText("message")
                                .toString())
                .contains("id is required");
        assertThat(
                        call(
                                        "format",
                                        "schema",
                                        "schema_text",
                                        SCHEMA,
                                        "document",
                                        "{\"id\":\"ABC\",\"total\":1,\"lines\":[{\"qty\":1},{\"qty\":1},{\"qty\":1},{\"qty\":1}]}")
                                .get("items")
                                .findValuesAsText("name")
                                .toString())
                .contains("/lines");
    }

    @Test
    void aSchemaItCannotFullyEnforceIsAFailedCheck() throws Exception {
        JsonNode r =
                call(
                        "format",
                        "schema",
                        "schema_text",
                        "{\"type\":\"string\",\"pattern\":\"^a+$\"}",
                        "document",
                        "\"aaa\"");
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.get("error").asText()).contains("pattern").contains("not supported");
    }

    @Test
    void aDocumentOrSchemaThatIsNotJsonIsAFailedCheck() throws Exception {
        assertThat(
                        call("format", "schema", "schema_text", SCHEMA, "document", "{oops")
                                .get("status")
                                .asText())
                .isEqualTo("failed");
        assertThat(
                        call("format", "schema", "schema_text", "{oops", "document", "{}")
                                .get("error")
                                .asText())
                .contains("schema");
        assertThat(
                        call("format", "schema", "schema_text", "[1]", "document", "{}")
                                .get("error")
                                .asText())
                .contains("schema");
        assertThat(call("format", "schema", "document", "{}").asText())
                .startsWith("Error:")
                .contains("schema");
    }

    @Test
    void schemasAndDocumentsAreReadFromFilesUnderTheRoot() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(dir.resolve("out/invoice.schema.json"), SCHEMA);
        Files.writeString(
                dir.resolve("out/invoice.json"),
                "{\"id\":\"INV-2\",\"total\":3,\"lines\":[{\"qty\":1}]}");
        JsonNode r =
                call(
                        Map.of("root", "out"),
                        "format",
                        "schema",
                        "schema",
                        "invoice.schema.json",
                        "source",
                        "invoice.json");
        assertThat(r.get("status").asText()).isEqualTo("passed");
    }

    @Test
    void aDeeplyNestedSchemaIsRefused() throws Exception {
        String deep = "{\"items\":".repeat(80) + "{}" + "}".repeat(80);
        JsonNode r = call("format", "schema", "schema_text", deep, "document", "[]");
        assertThat(r.get("status").asText()).isEqualTo("failed");
        assertThat(r.get("error").asText()).contains("nested");
    }

    @Test
    void aCanaryInATestNameStaysInTheItemsAndNowhereTyped() throws Exception {
        JsonNode r =
                call(
                        "format",
                        "junit",
                        "report",
                        "<testsuite><testcase classname=\"A\" name=\"mock canary IGNORE PREVIOUS\"><failure/></testcase></testsuite>");
        assertThat(r.get("items").toString()).contains("canary");
        r.fieldNames()
                .forEachRemaining(
                        f -> {
                            if (!f.equals("items"))
                                assertThat(r.get(f).toString()).doesNotContain("canary");
                        });
    }
}
