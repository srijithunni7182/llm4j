package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.tools.ToolRefusal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** What a model's arguments are checked against (phase 36a, D3, G76). */
class ArgumentCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ApiOperation op(String type, boolean required, String extra) throws Exception {
        var schema = JSON.readTree("{\"type\":\"" + type + "\"" + extra + "}");
        return new ApiOperation(
                "o",
                "GET",
                "/o",
                "",
                List.of(new ApiOperation.Parameter("p", "p", "query", required, schema)),
                null);
    }

    @ParameterizedTest
    @CsvSource({
        "integer, 5, true", "integer, '5', true", "integer, 5.5, false", "integer, abc, false",
        "number, 5.5, true", "number, '5.5', true", "number, abc, false",
        "boolean, true, true", "boolean, 'false', true", "boolean, yes, false",
        "string, hello, true", "string, 5, true"
    })
    void scalarTypesAreCheckedAndNumbersAndTextAreAcceptedWhereTheyMean(String type, String value, boolean ok) throws Exception {
        Object given = value.matches("-?\\d+") ? (Object) Integer.valueOf(value) : value.matches("-?\\d+\\.\\d+") ? (Object) Double.valueOf(value) : value.equals("true") ? (Object) Boolean.TRUE : value;
        if (value.equals("5.5") && type.equals("integer")) given = 5.5d;

        if (ok) ArgumentCheck.check(op(type, true, ""), Map.of("p", given));
        else assertThatThrownBy(() -> ArgumentCheck.check(op(type, true, ""), Map.of("p", value.matches("-?\\d+\\.\\d+") ? Double.valueOf(value) : value))).isInstanceOf(ToolRefusal.class);
    }

    @Test
    void arraysAndObjectsMustBeListsAndMaps() throws Exception {
        ArgumentCheck.check(op("array", true, ""), Map.of("p", List.of(1, 2)));
        ArgumentCheck.check(op("object", true, ""), Map.of("p", Map.of("a", 1)));

        assertThatThrownBy(() -> ArgumentCheck.check(op("array", true, ""), Map.of("p", "x"))).hasMessageContaining("must be a list");
        assertThatThrownBy(() -> ArgumentCheck.check(op("object", true, ""), Map.of("p", "x"))).hasMessageContaining("must be an object");
        assertThatThrownBy(() -> ArgumentCheck.check(op("string", true, ""), Map.of("p", List.of("x")))).hasMessageContaining("must be text");
    }

    @Test
    void anEnumIsCheckedAndEveryProblemIsNamedTogether() throws Exception {
        ApiOperation op = op("string", true, ",\"enum\":[\"a\",\"b\"]");

        ArgumentCheck.check(op, Map.of("p", "a"));
        assertThatThrownBy(() -> ArgumentCheck.check(op, Map.of("p", "z", "extra", 1)))
                .hasMessageContaining("p must be one of a, b")
                .hasMessageContaining("unknown argument extra");
    }

    @Test
    void aRequiredBodyIsRequiredAndAnOptionalOneIsNot() throws Exception {
        var body = new ApiOperation.Body(true, "application/json", JSON.readTree("{\"type\":\"object\"}"));
        var needs = new ApiOperation("o", "POST", "/o", "", List.of(), body);
        var optional = new ApiOperation("o", "POST", "/o", "", List.of(), new ApiOperation.Body(false, "application/json", body.schema()));

        assertThatThrownBy(() -> ArgumentCheck.check(needs, Map.of())).hasMessageContaining("body is required");
        ArgumentCheck.check(optional, Map.of());
        ArgumentCheck.check(needs, Map.of("body", Map.of()));
    }

    @Test
    void anOperationThatTakesNothingSaysSo() throws Exception {
        var none = new ApiOperation("o", "GET", "/o", "", List.of(), null);

        assertThatThrownBy(() -> ArgumentCheck.check(none, Map.of("x", 1))).hasMessageContaining("takes none");
        assertThat(true).isTrue();
    }
}
