package io.github.llm4j.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The structured-output request field (docs/specs/30-native-json-output, R1). */
class ResponseFormatTest {

    private static final Map<String, Object> OBJECT_SCHEMA =
            Map.of("type", "object", "properties", Map.of("vendor", Map.of("type", "string")));

    @Test
    void holdsANameASchemaAndStrictness() {
        ResponseFormat format = ResponseFormat.jsonSchema("invoice", OBJECT_SCHEMA);

        assertThat(format.name()).isEqualTo("invoice");
        assertThat(format.schema()).isEqualTo(OBJECT_SCHEMA);
        assertThat(format.strict()).isTrue();
    }

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> ResponseFormat.jsonSchema(" ", OBJECT_SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsASchemaThatIsNotAnObject() {
        assertThatThrownBy(() -> ResponseFormat.jsonSchema("x", Map.of("type", "string")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object");
    }

    @Test
    void copiesTheSchemaSoALaterChangeCannotReachIt() {
        Map<String, Object> mutable = new java.util.HashMap<>(OBJECT_SCHEMA);
        ResponseFormat format = ResponseFormat.jsonSchema("invoice", mutable);

        mutable.put("type", "array");

        assertThat(format.schema()).containsEntry("type", "object");
        assertThatThrownBy(() -> format.schema().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRequestCarriesItThroughToBuilderAndEquality() {
        ResponseFormat format = ResponseFormat.jsonSchema("invoice", OBJECT_SCHEMA);
        LLMRequest request =
                LLMRequest.builder().addUserMessage("hi").responseFormat(format).build();

        assertThat(request.getResponseFormat()).isEqualTo(format);
        assertThat(request.toBuilder().build()).isEqualTo(request);
        assertThat(request.toBuilder().build().getResponseFormat()).isEqualTo(format);
    }

    @Test
    void aRequestWithoutOneIsTheRequestItAlwaysWas() {
        LLMRequest plain = LLMRequest.builder().addUserMessage("hi").build();

        assertThat(plain.getResponseFormat()).isNull();
        assertThat(plain)
                .isNotEqualTo(
                        plain.toBuilder()
                                .responseFormat(ResponseFormat.jsonSchema("x", OBJECT_SCHEMA))
                                .build());
    }
}
