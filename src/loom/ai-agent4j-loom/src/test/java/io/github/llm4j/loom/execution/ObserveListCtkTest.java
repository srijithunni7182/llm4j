package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ObserveStmt;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * CTK scenario {@code observe_list}: an observe expression lists values with commas or joins them
 * with {@code +}, and either way becomes a text of {@code {name}} placeholders.
 */
class ObserveListCtkTest {

    @Test
    void aListAndAJoinBothBecomePlaceholderTexts() throws Exception {
        LoomScript script = new LoomLoader().load(Path.of("../ctk/scripts/observe_list.loom").toString());

        var body = script.getWorkflows().get(0).getStatements();
        assertThat(((ObserveStmt) body.get(2)).getExpression()).isEqualTo("{result}, {label}");
        assertThat(((ObserveStmt) body.get(3)).getExpression()).isEqualTo("{result} and {label}");
    }
}
