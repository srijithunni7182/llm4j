package io.github.llm4j.loom.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.AskStmt;
import io.github.llm4j.loom.ast.SchemaDef;
import io.github.llm4j.loom.execution.ExpectedShape;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An answer from outside is checked against the shape the script declared before the workflow sees it. */
class ExpectedShapeTest {

    private static SchemaDef shape(String declaration) {
        var ask = (AskStmt) A2AParseTest.parse("workflow W() { ask Desk \"q\" -> r expecting " + declaration + " }")
                .getWorkflows().get(0).getStatements().get(0);
        return ask.getExpecting();
    }

    @Test
    void anObjectWithTheRightFieldsPasses() {
        var s = shape("{ currency: enum[\"USD\", \"INR\"], total: number, ok: boolean, note: string }");
        assertThat(ExpectedShape.problem(s, Map.of("currency", "USD", "total", 12.5, "ok", true, "note", "x"))).isNull();
    }

    @Test
    void aMissingFieldAWrongTypeAndAnEnumOutsideItsValuesAreNamed() {
        var s = shape("{ currency: enum[\"USD\", \"INR\"], total: number }");
        assertThat(ExpectedShape.problem(s, Map.of("currency", "USD"))).contains("total");
        assertThat(ExpectedShape.problem(s, Map.of("currency", "EUR", "total", 1))).contains("currency").contains("USD");
        assertThat(ExpectedShape.problem(s, Map.of("currency", "USD", "total", "twelve"))).contains("total").contains("number");
    }

    @Test
    void aListChecksEveryElementAndASchemaThatIsNotAnObjectIsChecked() {
        var s = shape("{ tags: list<string> }");
        assertThat(ExpectedShape.problem(s, Map.of("tags", List.of("a", "b")))).isNull();
        assertThat(ExpectedShape.problem(s, Map.of("tags", List.of("a", 2)))).contains("tags");
        assertThat(ExpectedShape.problem(s, "not an object")).contains("object");
        assertThat(ExpectedShape.problem(s, null)).isNotNull();
    }

    @Test
    void extraFieldsFromOutsideAreIgnored() {
        var s = shape("{ total: number }");
        assertThat(ExpectedShape.problem(s, Map.of("total", 1, "surprise", "x"))).isNull();
    }
}
