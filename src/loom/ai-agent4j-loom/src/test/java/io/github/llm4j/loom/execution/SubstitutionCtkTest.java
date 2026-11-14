package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CTK scenario {@code substitution_braces}: only {@code {name}} is replaced; a word stays a word. */
class SubstitutionCtkTest {

    @Test
    void aWordThatIsAVariablesNameStaysAsWritten() throws Exception {
        LoomScript script =
                new LoomLoader().load(Path.of("../ctk/scripts/substitution_braces.loom").toString());

        DelegateStmt delegate = (DelegateStmt) script.getWorkflows().get(0).getStatements().get(0);
        assertThat(delegate.getPayload()).isEqualTo("length out of range: {out}");
    }
}
