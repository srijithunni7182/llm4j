package io.github.llm4j.loom.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.ast.ExtractStmt;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.RunStmt;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Phase 24, R1 and R2: the {@code extractor} block and the {@code extract} statement parse. */
class ExtractorParsingTest {

    static final String INVOICE =
            """
            extractor Invoice {
                model: "gemini-2.5-flash"
                instructions: "Read the invoice. Leave a field empty when the text does not state it."
                fields {
                    vendor: string
                    invoice_number: string
                    total: number
                    currency: enum["USD", "EUR", "INR", "OTHER"]
                }
                optional: [total]
                pii: mask
                timeout: 20s
            }
            """;

    static LoomScript parse(String source) {
        return new LoomParser(new Lexer(source).tokenize()).parseScript();
    }

    @Test
    void anExtractorDeclaresItsModelFieldsAndOptionalFields() {
        LoomScript script = parse(INVOICE);

        ExtractorDef def = script.findExtractor("Invoice");
        assertThat(def).isNotNull();
        assertThat(def.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(def.getInstructions()).startsWith("Read the invoice");
        assertThat(def.getFields().getFields().keySet())
                .containsExactly("vendor", "invoice_number", "total", "currency");
        assertThat(def.getOptional()).containsExactly("total");
        assertThat(def.getPii()).isEqualTo("mask");
        assertThat(def.getTimeoutMillis()).isEqualTo(20_000);
        assertThat(def.getTemperature()).isZero();
        assertThat(def.getLine()).isEqualTo(1);
    }

    @Test
    void instructionsCanComeFromAPromptFile() {
        ExtractorDef def =
                parse(
                                """
                extractor Invoice {
                    model: "m"
                    instructions: prompt "invoice-reader"
                    fields { vendor: string }
                }
                """)
                        .findExtractor("Invoice");

        assertThat(def.getInstructions()).isNull();
        assertThat(def.getPromptRef()).isEqualTo("invoice-reader");
    }

    @Test
    void extractAcceptsOneInputOrSeveralNamedInputs() {
        LoomScript script =
                parse(
                        INVOICE
                                + """
                workflow Main(text, msg, record) {
                    extract Invoice from text -> invoice retry 1 backoff 2s timeout 30s on_failure { halt "no: {_error}" }
                    extract Invoice from (email = msg, order = record) -> claim
                }
                """);

        List<io.github.llm4j.loom.ast.Statement> body = script.getWorkflows().get(0).getStatements();
        ExtractStmt one = (ExtractStmt) body.get(0);
        assertThat(one.getExtractor()).isEqualTo("Invoice");
        assertThat(one.getInputs()).hasSize(1);
        assertThat(one.getInputs().get(0).name()).isEmpty();
        assertThat(one.getInputs().get(0).kind()).isEqualTo(RunStmt.Kind.REFERENCE);
        assertThat(one.getInputs().get(0).text()).isEqualTo("text");
        assertThat(one.getVariableName()).isEqualTo("invoice");
        assertThat(one.getRetryCount()).isEqualTo(1);
        assertThat(one.getBackoffMillis()).isEqualTo(2_000);
        assertThat(one.getTimeoutMillis()).isEqualTo(30_000);
        assertThat(one.getOnFailure()).hasSize(1);

        ExtractStmt several = (ExtractStmt) body.get(1);
        assertThat(several.getInputs())
                .extracting(RunStmt.Arg::name)
                .containsExactly("email", "order");
        assertThat(several.getInputs())
                .extracting(RunStmt.Arg::text)
                .containsExactly("msg", "record");
    }

    @Test
    void anInputCanBeAQuotedTextWithPlaceholders() {
        ExtractStmt stmt =
                (ExtractStmt)
                        parse(
                                        INVOICE
                                                + "workflow Main(t) { extract Invoice from \"Invoice: {t}\" -> r }")
                                .getWorkflows()
                                .get(0)
                                .getStatements()
                                .get(0);

        assertThat(stmt.getInputs().get(0).kind()).isEqualTo(RunStmt.Kind.STRING);
    }

    @Test
    void extractRequiresAVariable() {
        assertThatThrownBy(() -> parse(INVOICE + "workflow Main(t) { extract Invoice from t }"))
                .hasMessageContaining("->")
                .hasMessageContaining("extract Invoice from t -> ");
    }

    @Test
    void extractAndExtractorAreContextualWords() {
        LoomScript script =
                parse(
                        """
                agent extractor { model: "m" system: "s" }
                workflow Main(extract) {
                    set extract = "x"
                    delegate "go {extract}" to extractor -> extract_result
                }
                """);

        assertThat(script.getAgents()).extracting("name").contains("extractor");
        assertThat(script.getExtractors()).isEmpty();
    }

    @Test
    void mergeKeepsEveryExtractor() {
        LoomScript base = parse("agent A { model: \"m\" system: \"s\" }");
        base.merge(parse(INVOICE));

        assertThat(base.findExtractor("Invoice")).isNotNull();
    }

    @Test
    void anExtractorNeedsAModelAndAtLeastOneField() {
        assertThatThrownBy(() -> parse("extractor E { fields { a: string } }"))
                .hasMessageContaining("needs a model");
        assertThatThrownBy(() -> parse("extractor E { model: \"m\" }"))
                .hasMessageContaining("needs fields");
        assertThatThrownBy(() -> parse("extractor E { model: \"m\" fields { } }"))
                .hasMessageContaining("at least one field");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "tools: [web_search]|tools",
                "memory { max_tokens: 100 }|memory",
                "steerable: true|steerable",
                "persona: Mentor|persona",
                "skills: [a]|skills",
                "grounding: web|grounding",
                "max_iterations: 3|max_iterations",
                "context { strategy: drop_oldest }|context",
                "approve: [Mail]|approve",
            })
    void settingsAnExtractorCannotHaveAreRefusedWithAReasonAndALine(String setting, String word) {
        String source =
                "extractor E {\n    model: \"m\"\n    fields { a: string }\n    "
                        + setting
                        + "\n}\n";

        assertThatThrownBy(() -> parse(source))
                .hasMessageContaining("an extractor is stateless; use an agent for this")
                .hasMessageContaining(word)
                .hasMessageContaining("line 4");
    }

    @Test
    void anUnknownSettingNamesTheNearestOne() {
        assertThatThrownBy(() -> parse("extractor E { model: \"m\" field { a: string } }"))
                .hasMessageContaining("fields");
    }

    @Test
    void aFieldWrittenTwiceAndAnOptionalNameThatIsNotAFieldAreRefused() {
        assertThatThrownBy(
                        () ->
                                parse(
                                        "extractor E { model: \"m\" fields { a: string, a: number } }"))
                .hasMessageContaining("a is written twice");
        assertThatThrownBy(
                        () ->
                                parse(
                                        "extractor E { model: \"m\" fields { a: string } optional: [b] }"))
                .hasMessageContaining("optional")
                .hasMessageContaining("b is not a field");
    }
}
