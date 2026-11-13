package io.github.llm4j.tools.compute;

import static io.github.llm4j.tools.compute.ComputeToolTest.call;
import static io.github.llm4j.tools.compute.ComputeToolTest.value;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The exact edges of {@code compute}: every limit at and one past its value, spacing, messages. */
class ExpressionLimitsTest {

    static String calc(String expression) throws Exception {
        return value("action", "calc", "expression", expression);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "' ( 1 + 2 ) * 3 ' | 9",
                "'1\t+\t2' | 3",
                "'round ( 2.5 )' | 3",
                "'min( 1 , 2 )' | 1",
                "'abs (-1)' | 1",
                "'- 1' | -1",
                "'+5' | 5",
                "'2 * - 3' | -6",
                "'8 / 2 / 2' | 2",
                "'10 - 2 - 3' | 5",
                "'.5 + .5' | 1.0",
                "'max(-1, -2)' | -1",
                "'round(1.005, 2)' | 1.01",
                "'round(7, 0)' | 7"
            })
    void spacingSignsAndOrderAreRead(String expression, String expected) throws Exception {
        assertThat(calc(expression)).isEqualTo(expected);
    }

    @Test
    void theFirstOfTwoEqualValuesIsKeptByMinAndMax() throws Exception {
        assertThat(calc("min(2.0, 2)")).isEqualTo("2.0");
        assertThat(calc("max(2.0, 2)")).isEqualTo("2.0");
        assertThat(calc("min(2, 2.0)")).isEqualTo("2");
    }

    @Test
    void theLengthLimitIsExact() throws Exception {
        String at = "1" + "+1".repeat(499) + " "; // 1000 characters
        assertThat(at).hasSize(1000);
        assertThat(calc(at)).isEqualTo("500");
        assertThat(calc(at + " "))
                .isEqualTo("Error: calc: the expression is longer than 1000 characters");
    }

    @Test
    void theNestingLimitIsExact() throws Exception {
        assertThat(calc("(".repeat(32) + "1" + ")".repeat(32))).isEqualTo("1");
        assertThat(calc("(".repeat(33) + "1" + ")".repeat(33)))
                .isEqualTo("Error: calc: the expression is nested more than 32 deep");
        assertThat(calc("abs(".repeat(32) + "1" + ")".repeat(32))).isEqualTo("1");
        assertThat(calc("abs(".repeat(33) + "1" + ")".repeat(33))).contains("nested more than 32");
    }

    @Test
    void theDigitLimitsAreExact() throws Exception {
        assertThat(calc("9".repeat(100))).isEqualTo("9".repeat(100));
        assertThat(calc("9".repeat(101)))
                .isEqualTo("Error: calc: a number has more than 100 digits");
        assertThat(calc("0." + "1".repeat(99))).startsWith("0.1");
        // 4 factors of 100 digits is at most 400 digits; a fifth goes past it
        String n = "9".repeat(100);
        assertThat(calc(n + "*" + n + "*" + n + "*" + n)).hasSize(400);
        assertThat(calc(n + "*" + n + "*" + n + "*" + n + "*9"))
                .isEqualTo("Error: calc: a result has more than 400 digits");
        assertThat(calc("1 / 3 * 1" + "0".repeat(99))).doesNotStartWith("Error");
        assertThat(calc("round(1, 100)")).hasSize(102);
    }

    @Test
    void theMessagesNameThePlace() throws Exception {
        assertThat(calc("2 ^ 3")).isEqualTo("Error: calc: unexpected '^ 3' at position 3");
        assertThat(calc("1 + 2 @ 345678901234"))
                .isEqualTo("Error: calc: unexpected '@ 34567890…' at position 7");
        assertThat(calc("1 + 2 @ 34567890"))
                .isEqualTo("Error: calc: unexpected '@ 34567890' at position 7");
        assertThat(calc("(1 + 2")).isEqualTo("Error: calc: missing ) at position 7");
        assertThat(calc("min(1, 2")).isEqualTo("Error: calc: missing ) after the arguments of min");
        assertThat(calc("pi"))
                .isEqualTo("Error: calc: unknown name pi; it knows round, min, max and abs");
        assertThat(calc("abs(1, 2)")).isEqualTo("Error: calc: abs takes one number");
        assertThat(calc("round(1, -1)")).contains("from 0 to 100");
        assertThat(calc("round(1, 1.5)")).contains("from 0 to 100");
        assertThat(calc("round(1, 101)")).contains("from 0 to 100");
        assertThat(calc("max()")).isEqualTo("Error: calc: max takes at least one number");
    }

    @Test
    void dateAmountsAreBoundedExactly() throws Exception {
        assertThat(value("action", "date", "op", "add", "date", "2000-01-01", "days", 1_000_000))
                .doesNotStartWith("Error");
        assertThat(value("action", "date", "op", "add", "date", "2000-01-01", "days", 1_000_001))
                .startsWith("Error:");
        assertThat(value("action", "date", "op", "add", "date", "2000-01-01", "days", -1_000_000))
                .doesNotStartWith("Error");
        assertThat(value("action", "date", "op", "add", "date", "2000-01-01", "days", "1.0"))
                .isEqualTo("2000-01-02");
    }

    @Test
    void jsonPathsAreReadExactly() throws Exception {
        String doc = "{\"a\":[[1,2],[3,4]],\"b\":{\"c\":\"x\"}}";
        assertThat(call("action", "json", "document", doc, "path", "a[1][0]").get("value").asInt())
                .isEqualTo(3);
        assertThat(value("action", "json", "document", doc, "path", "a[1"))
                .contains("not of the form");
        assertThat(value("action", "json", "document", doc, "path", "a[x]"))
                .contains("not of the form");
        assertThat(value("action", "json", "document", doc, "path", ".a"))
                .contains("not of the form");
        assertThat(value("action", "json", "document", doc, "path", "b[0]"))
                .contains("no value at b[0]");
        assertThat(value("action", "json", "document", doc, "path", "a.c"))
                .contains("no value at a.c");
        assertThat(call("action", "json", "document", doc).get("value").get("b").get("c").asText())
                .isEqualTo("x");
    }

    @Test
    void sizesAreBoundedExactly() throws Exception {
        String atLimit = "\"" + "x".repeat(1022) + "\""; // 1024 bytes
        assertThat(
                        call(Map.of("max_bytes", "1k"), "action", "json", "document", atLimit)
                                .get("value")
                                .asText())
                .hasSize(1022);
        assertThat(
                        call(Map.of("max_bytes", "1k"), "action", "json", "document", atLimit + " ")
                                .asText())
                .isEqualTo("Error: document is larger than 1024 bytes (max_bytes)");
    }

    @Test
    void csvLimitsAndCountsAreExact() throws Exception {
        String ten = "a\n" + "1\n".repeat(10);
        assertThat(
                        call(
                                        Map.of("max_rows", "10"),
                                        "action",
                                        "csv",
                                        "text",
                                        ten,
                                        "column",
                                        "a",
                                        "op",
                                        "count")
                                .get("rows")
                                .asInt())
                .isEqualTo(10);
        StringBuilder exact = new StringBuilder("k,v\n");
        for (int i = 0; i < 1000; i++) exact.append("g").append(i).append(",1\n");
        assertThat(
                        call(
                                        "action",
                                        "csv",
                                        "text",
                                        exact.toString(),
                                        "column",
                                        "v",
                                        "op",
                                        "count",
                                        "group_by",
                                        "k")
                                .get("truncated")
                                .asBoolean())
                .isFalse();
        var grouped =
                call(
                        "action",
                        "csv",
                        "text",
                        "k,v\na,1\nb,\na,2\n",
                        "column",
                        "v",
                        "op",
                        "mean",
                        "group_by",
                        "k");
        assertThat(grouped.get("rows").asInt()).isEqualTo(3);
        assertThat(grouped.get("groups").get("a").asText()).isEqualTo("1.5");
        assertThat(grouped.get("groups").get("b").asText()).isEmpty();
        var short_ =
                call(
                        "action",
                        "csv",
                        "text",
                        "k,v\nonly\n",
                        "column",
                        "v",
                        "op",
                        "min",
                        "group_by",
                        "k");
        assertThat(short_.get("groups").get("only").asText()).isEmpty();
        var none = call("action", "csv", "text", "v\n\n", "column", "v", "op", "max");
        assertThat(none.get("value").asText()).isEmpty();
        assertThat(none.get("rows").asInt()).isZero();
        var missingGroup =
                call(
                        "action",
                        "csv",
                        "text",
                        "v,k\n1\n",
                        "column",
                        "v",
                        "op",
                        "sum",
                        "group_by",
                        "k");
        assertThat(missingGroup.get("groups").get("").asText()).isEqualTo("1");
    }
}
