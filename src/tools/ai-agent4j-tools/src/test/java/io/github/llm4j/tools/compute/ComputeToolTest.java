package io.github.llm4j.tools.compute;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.ComputeKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Phase 35, D13 and G62: {@code compute} is exact, bounded and deterministic. */
class ComputeToolTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static Tool tool(Map<String, String> options) throws Exception {
        return new ComputeKind().create("Calc", options, Path.of("."), new RecordingEffects());
    }

    static JsonNode call(Map<String, String> options, Object... args) throws Exception {
        Map<String, Object> a = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = tool(options).execute(a);
        if (out.startsWith("Error:")) return JSON.getNodeFactory().textNode(out);
        return JSON.readTree(out);
    }

    static JsonNode call(Object... args) throws Exception {
        return call(Map.of(), args);
    }

    static String value(Object... args) throws Exception {
        JsonNode n = call(args);
        return n.isTextual() ? n.asText() : n.get("value").asText();
    }

    // ---- the kind

    @Test
    void itReachesNothingAndChangesNothing() {
        ToolProfile p = new ComputeKind().profile(Map.of());
        assertThat(p.known()).isTrue();
        assertThat(p.untrusted() || p.privateData() || p.outward()).isFalse();
        assertThat(p.effect()).isEqualTo(TaskEffect.NONE);
        assertThat(p.journaled()).isFalse();
        assertThat(new ComputeKind().uncheckedResult(Map.of())).isEmpty();
    }

    @Test
    void itsOptionsAreChecked() {
        assertThat(new ComputeKind().check(Map.of(), Path.of("."))).isNull();
        assertThat(new ComputeKind().check(Map.of("max_rows", "0"), Path.of(".")))
                .contains("max_rows");
        assertThat(new ComputeKind().check(Map.of("max_bytes", "1g"), Path.of(".")))
                .contains("max_bytes");
    }

    @Test
    void anUnknownActionIsRefused() throws Exception {
        assertThat(value("action", "regex", "expression", "1"))
                .startsWith("Error:")
                .contains("calc");
        assertThat(value("expression", "1")).startsWith("Error:").contains("action");
    }

    // ---- calc

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0.1 + 0.2 | 0.3",
                "1.50 * 2 | 3.00",
                "2 + 3 * 4 | 14",
                "(2 + 3) * 4 | 20",
                "-3 + 5 | 2",
                "- (2 - 5) | 3",
                "10 % 4 | 2",
                "7 / 2 | 3.5",
                "round(2.675, 2) | 2.68",
                "round(2.5) | 3",
                "min(4, 2.5, 9) | 2.5",
                "max(4, 2.5, 9) | 9",
                "abs(-7.25) | 7.25",
                "1 - 0.9 | 0.1",
                "123456789012345678901234567890 + 1 | 123456789012345678901234567891"
            })
    void calcIsExactDecimalArithmetic(String expression, String expected) throws Exception {
        assertThat(value("action", "calc", "expression", expression)).isEqualTo(expected);
    }

    @Test
    void aDivisionThatDoesNotEndIsRoundedAndSaysSo() throws Exception {
        JsonNode n = call("action", "calc", "expression", "1 / 3");
        assertThat(n.get("value").asText()).startsWith("0.3333333333").hasSize(36);
        assertThat(n.get("rounded").asBoolean()).isTrue();
        assertThat(call("action", "calc", "expression", "1 / 4").get("rounded").asBoolean())
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "1 / 0 | division by zero",
                "5 % 0 | division by zero",
                "1 + | ends too early",
                "1 + 2) | unexpected",
                "(1 + 2 | missing )",
                "2 ^ 3 | unexpected",
                "sqrt(4) | unknown function",
                "round(1, 2, 3) | round takes",
                "round(1, 101) | round",
                "min() | min takes",
                "1.2.3 | unexpected",
                "abc | unknown"
            })
    void badExpressionsAreRefusedWithAReason(String expression, String reason) throws Exception {
        assertThat(value("action", "calc", "expression", expression))
                .startsWith("Error:")
                .contains(reason);
    }

    @Test
    void calcIsBounded() throws Exception {
        assertThat(value("action", "calc", "expression", "1+".repeat(600) + "1"))
                .startsWith("Error:")
                .contains("longer than");
        assertThat(value("action", "calc", "expression", "(".repeat(40) + "1" + ")".repeat(40)))
                .startsWith("Error:")
                .contains("nested");
        assertThat(value("action", "calc", "expression", "1" + "0".repeat(120)))
                .startsWith("Error:")
                .contains("digits");
        String big = "9".repeat(90);
        String grows = String.join(" * ", java.util.Collections.nCopies(5, big));
        assertThat(value("action", "calc", "expression", grows))
                .startsWith("Error:")
                .contains("digits");
        assertThat(value("action", "calc")).startsWith("Error:").contains("expression");
    }

    // ---- date

    @Test
    void dateUsesTheInjectedClock() throws Exception {
        assertThat(value("action", "date", "op", "today")).isEqualTo("2026-10-01");
    }

    @Test
    void dateAddsAndSubtracts() throws Exception {
        assertThat(value("action", "date", "op", "add", "date", "2026-01-31", "months", 1))
                .isEqualTo("2026-02-28");
        assertThat(
                        value(
                                "action",
                                "date",
                                "op",
                                "add",
                                "date",
                                "2026-11-13",
                                "days",
                                -14,
                                "years",
                                "1"))
                .isEqualTo("2027-10-30");
    }

    @Test
    void dateTellsTheDaysBetweenAndTheWeekday() throws Exception {
        assertThat(
                        call(
                                        "action",
                                        "date",
                                        "op",
                                        "between",
                                        "date",
                                        "2026-11-01",
                                        "to",
                                        "2026-11-13")
                                .get("days")
                                .asLong())
                .isEqualTo(12);
        assertThat(
                        call(
                                        "action",
                                        "date",
                                        "op",
                                        "between",
                                        "date",
                                        "2026-11-13",
                                        "to",
                                        "2026-11-01")
                                .get("days")
                                .asLong())
                .isEqualTo(-12);
        assertThat(value("action", "date", "op", "weekday", "date", "2026-11-13"))
                .isEqualTo("FRIDAY");
    }

    @Test
    void badDatesAreRefused() throws Exception {
        assertThat(value("action", "date", "op", "add", "date", "13/11/2026", "days", 1))
                .startsWith("Error:")
                .contains("yyyy-MM-dd");
        assertThat(value("action", "date", "op", "add", "date", "2026-11-13", "days", 10_000_000))
                .startsWith("Error:")
                .contains("days");
        assertThat(value("action", "date", "op", "add", "date", "2026-11-13", "days", "x"))
                .startsWith("Error:")
                .contains("whole number");
        assertThat(value("action", "date", "op", "soon")).startsWith("Error:").contains("op");
        assertThat(value("action", "date", "op", "between", "date", "2026-11-13"))
                .startsWith("Error:")
                .contains("to");
    }

    // ---- json

    static final String DOC =
            "{\"order\":{\"lines\":[{\"sku\":\"A1\",\"qty\":2},{\"sku\":\"B7\",\"qty\":1}],"
                    + "\"total\":12.50,\"paid\":true}}";

    @Test
    void jsonPicksAValueByPath() throws Exception {
        assertThat(
                        call("action", "json", "document", DOC, "path", "order.lines[1].sku")
                                .get("value")
                                .asText())
                .isEqualTo("B7");
        assertThat(
                        call("action", "json", "document", DOC, "path", "order.total")
                                .get("value")
                                .decimalValue())
                .isEqualByComparingTo("12.50");
        assertThat(
                        call("action", "json", "document", DOC, "path", "order.lines")
                                .get("value")
                                .size())
                .isEqualTo(2);
        assertThat(call("action", "json", "document", "[5,6]", "path", "[1]").get("value").asInt())
                .isEqualTo(6);
    }

    @Test
    void jsonListsKeys() throws Exception {
        assertThat(
                        call("action", "json", "document", DOC, "path", "order", "op", "keys")
                                .get("keys")
                                .toString())
                .isEqualTo("[\"lines\",\"total\",\"paid\"]");
    }

    @Test
    void jsonSaysWhenThereIsNothingThereOrTheInputIsBad() throws Exception {
        assertThat(value("action", "json", "document", DOC, "path", "order.missing"))
                .startsWith("Error:")
                .contains("no value at order.missing");
        assertThat(value("action", "json", "document", DOC, "path", "order.lines[9]"))
                .startsWith("Error:")
                .contains("no value");
        assertThat(value("action", "json", "document", "{not json", "path", "a"))
                .startsWith("Error:")
                .contains("not JSON");
        assertThat(value("action", "json", "document", DOC, "path", "order..x"))
                .startsWith("Error:")
                .contains("path");
        assertThat(value("action", "json", "document", DOC, "path", "order", "op", "keys2"))
                .startsWith("Error:");
        assertThat(value("action", "json", "document", "[1]", "op", "keys"))
                .startsWith("Error:")
                .contains("not an object");
    }

    @Test
    void jsonIsBounded() throws Exception {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThat(value("action", "json", "document", deep, "path", "[0]"))
                .startsWith("Error:")
                .contains("nested");
        assertThat(
                        call(
                                        Map.of("max_bytes", "1kb"),
                                        "action",
                                        "json",
                                        "document",
                                        "\"" + "x".repeat(2000) + "\"")
                                .asText())
                .startsWith("Error:")
                .contains("larger than");
    }

    // ---- csv

    static final String SALES =
            "region,amount,note\n"
                    + "north,10.50,first\n"
                    + "south,4,\"with, a comma\"\n"
                    + "north,,empty amount\n"
                    + "\"north\",2.25,\"a \"\"quoted\"\" word\"\r\n";

    @Test
    void csvCountsSumsAndAverages() throws Exception {
        assertThat(value("action", "csv", "text", SALES, "column", "amount", "op", "sum"))
                .isEqualTo("16.75");
        JsonNode count = call("action", "csv", "text", SALES, "column", "amount", "op", "count");
        assertThat(count.get("value").asText()).isEqualTo("3");
        assertThat(count.get("rows").asInt()).isEqualTo(4);
        assertThat(count.get("skipped").asInt()).isEqualTo(1);
        assertThat(value("action", "csv", "text", SALES, "column", "amount", "op", "mean"))
                .isEqualTo("5.583333333333333333333333333333333");
        assertThat(value("action", "csv", "text", SALES, "column", "amount", "op", "min"))
                .isEqualTo("2.25");
        assertThat(value("action", "csv", "text", SALES, "column", "amount", "op", "max"))
                .isEqualTo("10.50");
    }

    @Test
    void csvGroupsByAColumn() throws Exception {
        JsonNode n =
                call(
                        "action",
                        "csv",
                        "text",
                        SALES,
                        "column",
                        "amount",
                        "op",
                        "sum",
                        "group_by",
                        "region");
        assertThat(n.get("groups").get("north").asText()).isEqualTo("12.75");
        assertThat(n.get("groups").get("south").asText()).isEqualTo("4");
        assertThat(n.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void csvRefusesWhatItCannotRead() throws Exception {
        assertThat(value("action", "csv", "text", SALES, "column", "price", "op", "sum"))
                .startsWith("Error:")
                .contains("no column price")
                .contains("region, amount, note");
        assertThat(value("action", "csv", "text", SALES, "column", "note", "op", "sum"))
                .startsWith("Error:")
                .contains("row 1")
                .contains("not a number")
                .doesNotContain("first");
        assertThat(value("action", "csv", "text", "a\n\"open", "column", "a", "op", "count"))
                .startsWith("Error:")
                .contains("quote");
        assertThat(value("action", "csv", "text", SALES, "column", "amount", "op", "median"))
                .startsWith("Error:");
        assertThat(value("action", "csv", "text", "", "column", "a", "op", "count"))
                .startsWith("Error:");
    }

    @Test
    void csvIsBounded() throws Exception {
        String rows = "a\n" + "1\n".repeat(20);
        assertThat(
                        call(
                                        Map.of("max_rows", "10"),
                                        "action",
                                        "csv",
                                        "text",
                                        rows,
                                        "column",
                                        "a",
                                        "op",
                                        "count")
                                .asText())
                .startsWith("Error:")
                .contains("more than 10 rows");
        StringBuilder many = new StringBuilder("k,v\n");
        for (int i = 0; i < 1100; i++) many.append("g").append(i).append(",1\n");
        JsonNode n =
                call(
                        "action",
                        "csv",
                        "text",
                        many.toString(),
                        "column",
                        "v",
                        "op",
                        "count",
                        "group_by",
                        "k");
        assertThat(n.get("truncated").asBoolean()).isTrue();
        assertThat(n.get("groups").size()).isEqualTo(1000);
    }

    @Test
    void theSameArgumentsGiveTheSameAnswer() throws Exception {
        for (int i = 0; i < 3; i++)
            assertThat(value("action", "calc", "expression", "1 / 7"))
                    .isEqualTo(value("action", "calc", "expression", "1 / 7"));
    }
}
