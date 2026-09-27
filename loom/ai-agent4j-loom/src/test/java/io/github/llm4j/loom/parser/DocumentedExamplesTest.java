package io.github.llm4j.loom.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.lexer.Lexer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The examples shown in the README and the guide must stay valid Loom. */
class DocumentedExamplesTest {

    @Test
    void theNewFeatureExamplesParse() throws Exception {
        String source;
        try (var in = getClass().getResourceAsStream("/docs/readme_examples.loom")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LoomScript script = new LoomParser(new Lexer(source).tokenize()).parseScript();
        assertEquals(1, script.getWorkflows().size());
        assertEquals(200_000L, script.getBudget().getTokens());
        assertEquals(2000, script.getAgents().stream().filter(a -> a.getName().equals("Writer"))
                .findFirst().orElseThrow().getBudget().getPerCall());
    }

    @Test
    void aBareListHoldsAnything() {
        LoomScript script = new LoomParser(new Lexer("""
                agent A { model: "m" output_schema: { items: list, names: list<string> } }
                """).tokenize()).parseScript();
        SchemaDef schema = script.getAgents().get(0).getOutputSchema();
        assertEquals(SchemaDef.Type.LIST, schema.getFields().get("items").getType());
        assertNull(schema.getFields().get("items").getElementType());
        assertEquals(SchemaDef.Type.STRING, schema.getFields().get("names").getElementType().getType());
    }
}
