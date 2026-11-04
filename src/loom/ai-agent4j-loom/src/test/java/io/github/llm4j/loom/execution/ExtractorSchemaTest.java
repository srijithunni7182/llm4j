package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.ResponseFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one schema every provider's strict rules accept (docs/specs/30-native-json-output, R3): all
 * fields required, optional ones nullable, nothing the strictest rule refuses, and a check against
 * the strictest limit.
 */
class ExtractorSchemaTest {

    private static ExtractorDef extractor(String fields, String optional) {
        String source =
                "extractor Invoice {\n"
                        + "    model: \"ollama/llama3\"\n"
                        + "    fields {\n"
                        + fields
                        + "\n    }\n"
                        + (optional.isEmpty() ? "" : "    optional: [" + optional + "]\n")
                        + "}\n"
                        + "workflow Main(text) {\n    extract Invoice from text -> invoice\n    note \"{invoice}\"\n}\n";
        return new LoomParser(new Lexer(source).tokenize())
                .parseScript()
                .findExtractor("Invoice");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @Test
    void everyFieldIsRequiredAndNothingElseIsAllowed() {
        ResponseFormat format =
                ExtractorSchema.of(extractor("        vendor: string\n        total: number", ""));

        assertThat(format.name()).isEqualTo("Invoice");
        assertThat(format.strict()).isTrue();
        assertThat(format.schema())
                .containsEntry("type", "object")
                .containsEntry("required", List.of("vendor", "total"))
                .containsEntry("additionalProperties", false);
        assertThat(property(format.schema(), "vendor")).isEqualTo(Map.of("type", "string"));
        assertThat(property(format.schema(), "total")).isEqualTo(Map.of("type", "number"));
    }

    @Test
    void anOptionalFieldIsRequiredButMayBeNull() {
        ResponseFormat format =
                ExtractorSchema.of(
                        extractor("        vendor: string\n        total: number", "total"));

        assertThat(format.schema().get("required")).isEqualTo(List.of("vendor", "total"));
        assertThat(property(format.schema(), "total"))
                .isEqualTo(Map.of("type", List.of("number", "null")));
        assertThat(property(format.schema(), "vendor")).isEqualTo(Map.of("type", "string"));
    }

    @Test
    void anEnumKeepsItsWordsAndAnOptionalOneAllowsNull() {
        ResponseFormat format =
                ExtractorSchema.of(
                        extractor("        kind: enum[\"a\", \"b\"]\n        tone: enum[\"x\", \"y\"]", "tone"));

        assertThat(property(format.schema(), "kind"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("a", "b")));
        Map<String, Object> tone = property(format.schema(), "tone");
        assertThat(tone.get("type")).isEqualTo(List.of("string", "null"));
        assertThat(tone.get("enum")).isEqualTo(java.util.Arrays.asList("x", "y", null));
    }

    @Test
    void listsAndNestedObjectsAreClosedToo() {
        ResponseFormat format =
                ExtractorSchema.of(
                        extractor(
                                "        lines: list<{ sku: string, qty: number }>",
                                ""));

        Map<String, Object> lines = property(format.schema(), "lines");
        assertThat(lines).containsEntry("type", "array");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) lines.get("items");
        assertThat(item)
                .containsEntry("type", "object")
                .containsEntry("required", List.of("sku", "qty"))
                .containsEntry("additionalProperties", false);
    }

    @Test
    void usesNoKeywordTheStrictestProviderRefuses() {
        ResponseFormat format =
                ExtractorSchema.of(
                        extractor(
                                "        vendor: string\n        lines: list<{ sku: string }>",
                                "lines"));

        assertThat(keywordsIn(format.schema()))
                .isSubsetOf(
                        "type",
                        "properties",
                        "required",
                        "additionalProperties",
                        "items",
                        "enum",
                        "anyOf");
    }

    @Test
    void aNameAProviderWouldRefuseIsMadeSafe() {
        assertThat(ExtractorSchema.safeName("Invoice")).isEqualTo("Invoice");
        assertThat(ExtractorSchema.safeName("My Invoice!")).isEqualTo("My_Invoice_");
        assertThat(ExtractorSchema.safeName("x".repeat(100))).hasSize(64);
    }

    @Test
    void aSmallExtractorFits() {
        assertThat(ExtractorSchema.tooBigBecause(extractor("        vendor: string", "")))
                .isEmpty();
    }

    @Test
    void moreNullableFieldsThanTheStrictestProviderTakesDoesNotFit() {
        StringBuilder fields = new StringBuilder();
        StringBuilder optional = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            fields.append("        f").append(i).append(": string\n");
            optional.append(i == 0 ? "" : ", ").append('f').append(i);
        }

        Optional<String> reason =
                ExtractorSchema.tooBigBecause(extractor(fields.toString(), optional.toString()));

        assertThat(reason).hasValueSatisfying(r -> assertThat(r).contains("17").contains("16"));
    }

    @Test
    void exactlySixteenNullableFieldsStillFit() {
        StringBuilder fields = new StringBuilder();
        StringBuilder optional = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            fields.append("        f").append(i).append(": string\n");
            optional.append(i == 0 ? "" : ", ").append('f').append(i);
        }

        assertThat(ExtractorSchema.tooBigBecause(extractor(fields.toString(), optional.toString())))
                .isEmpty();
    }

    @Test
    void nestingDeeperThanTheLimitDoesNotFit() {
        String deep = "string";
        for (int i = 0; i < 11; i++) deep = "list<" + deep + ">";

        assertThat(ExtractorSchema.tooBigBecause(extractor("        deep: " + deep, "")))
                .hasValueSatisfying(r -> assertThat(r).contains("deep").contains("10"));
    }

    private static java.util.Set<String> keywordsIn(Object node) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (node instanceof Map<?, ?> m) {
            Map<?, ?> props = (Map<?, ?>) m.get("properties");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.add(String.valueOf(e.getKey()));
                if (e.getKey().equals("properties")) {
                    for (Object child : ((Map<?, ?>) e.getValue()).values()) out.addAll(keywordsIn(child));
                } else if (e.getValue() instanceof Map || e.getValue() instanceof List) {
                    out.addAll(keywordsIn(e.getValue()));
                }
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) out.addAll(keywordsIn(o));
        }
        return out;
    }
}
