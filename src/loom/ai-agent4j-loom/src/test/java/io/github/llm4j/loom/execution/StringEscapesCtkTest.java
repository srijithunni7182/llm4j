package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.NoteStmt;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CTK scenario {@code string_escapes}: a quote and a backslash can be written inside a string. */
class StringEscapesCtkTest {

    private static final Path SCRIPT = Path.of("../ctk/scripts/string_escapes.loom");

    @Test
    void theEscapesAreReadAndEverythingElseStaysAsWritten() throws Exception {
        LoomScript script = new LoomLoader().load(SCRIPT.toString());
        var statements = script.getWorkflows().get(0).getStatements();

        DelegateStmt delegate = (DelegateStmt) statements.get(0);
        assertThat(delegate.getPayload()).isEqualTo("Say \"hello\" to {name} and sign off as C:\\Greeter\\");
        NoteStmt note = (NoteStmt) statements.get(1);
        assertThat(note.getMessage()).isEqualTo("asked for: \"hello\", a tab (\\t) and a line break (\\n) stay as written");
    }
}
