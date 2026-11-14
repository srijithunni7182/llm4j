package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 36a, D4, D12 and G88: a pin is stable under cosmetic changes and moves with a signature. */
class SpecPinTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ApiOperation.Parameter param(String name, String type, boolean required, String description) throws Exception {
        return new ApiOperation.Parameter(
                name,
                name,
                "query",
                required,
                JSON.readTree("{\"type\":\"" + type + "\",\"description\":\"" + description + "\"}"));
    }

    private static ApiOperation op(String summary, List<ApiOperation.Parameter> params, ApiOperation.Body body) {
        return new ApiOperation("find", "GET", "/pets", summary, params, body);
    }

    @Test
    void aPinHasThePrefixAndAShortHash() throws Exception {
        assertThat(SpecPin.of(op("s", List.of(), null))).matches("sha256-[0-9a-f]{16}");
    }

    @Test
    void rewordedDescriptionsAndSummariesAndParameterOrderDoNotFlipIt() throws Exception {
        var a = param("a", "string", true, "first");
        var b = param("b", "integer", false, "second");
        String pin = SpecPin.of(op("Finds pets", List.of(a, b), null));

        var reworded = op("Completely different words", List.of(param("b", "integer", false, "other"), param("a", "string", true, "another")), null);

        assertThat(SpecPin.of(reworded)).isEqualTo(pin);
    }

    @Test
    void aChangedTypeOrRequirednessOrNewParameterFlipsIt() throws Exception {
        String pin = SpecPin.of(op("s", List.of(param("a", "string", true, "d")), null));

        assertThat(SpecPin.of(op("s", List.of(param("a", "integer", true, "d")), null))).isNotEqualTo(pin);
        assertThat(SpecPin.of(op("s", List.of(param("a", "string", false, "d")), null))).isNotEqualTo(pin);
        assertThat(SpecPin.of(op("s", List.of(param("a", "string", true, "d"), param("b", "string", false, "d")), null))).isNotEqualTo(pin);
    }

    @Test
    void theBodyIsPartOfTheSignature() throws Exception {
        var json = new ApiOperation.Body(true, "application/json", JSON.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"string\"}}}"));
        var changed = new ApiOperation.Body(true, "application/json", JSON.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}}}"));
        var optional = new ApiOperation.Body(false, "application/json", json.schema());

        String pin = SpecPin.of(op("s", List.of(), json));

        assertThat(SpecPin.of(op("s", List.of(), null))).isNotEqualTo(pin);
        assertThat(SpecPin.of(op("s", List.of(), changed))).isNotEqualTo(pin);
        assertThat(SpecPin.of(op("s", List.of(), optional))).isNotEqualTo(pin);
    }

    @Test
    void theMethodAndPathAreInIt() throws Exception {
        var get = new ApiOperation("a", "GET", "/x", "", List.of(), null);

        assertThat(SpecPin.of(new ApiOperation("a", "POST", "/x", "", List.of(), null))).isNotEqualTo(SpecPin.of(get));
        assertThat(SpecPin.of(new ApiOperation("a", "GET", "/y", "", List.of(), null))).isNotEqualTo(SpecPin.of(get));
    }

    @Test
    void theOperationsNameAloneIsNot() throws Exception {
        assertThat(SpecPin.of(new ApiOperation("one", "GET", "/x", "", List.of(), null)))
                .isEqualTo(SpecPin.of(new ApiOperation("two", "GET", "/x", "", List.of(), null)));
    }
}
