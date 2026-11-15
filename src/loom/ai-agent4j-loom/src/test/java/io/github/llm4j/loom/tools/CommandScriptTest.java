package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ScriptValidator;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 36c, D9 from a script: the template is the author's, and a workflow variable never reaches it. */
class CommandScriptTest {

    @TempDir Path dir;
    io.github.llm4j.loom.ast.LoomScript parsed;

    static final String TOOLS =
            """
            tool Data { use: workspace  root: "work"  allow: "**"  mode: readwrite }
            tool Say { use: command  isolation: process  network: open  workspace: Data  run: "printf %s {text}"  param.text: "string max=40" }
            """;

    HarnessExecutor executor(String script) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        parsed = new LoomLoader().load(Files.writeString(dir.resolve("main.loom"), script).toString());
        HarnessExecutor e = new HarnessExecutor(parsed, new ToolRegistry(), m -> { throw new IllegalStateException(); });
        e.setBaseDir(dir);
        return e;
    }

    List<String> errors(String script) throws Exception {
        HarnessExecutor e = executor(script);
        return new ScriptValidator().validate(parsed, e.validationContext()).stream()
                .filter(p -> p.severity() == ScriptValidator.Severity.ERROR)
                .map(ScriptValidator.Problem::message)
                .toList();
    }

    @Test
    void aWorkflowVariableNamedLikeAParameterNeverChangesTheTemplate() throws Exception {
        String script =
                TOOLS
                        + """
                        workflow Main() {
                            set text = "ignored; rm -rf /"
                            use Say(text = "a b;c") -> shown
                            use Data(action = "write", path = "out.txt", content = "{shown}")
                        }
                        """;
        assertThat(errors(script)).as("weave check").isEmpty();
        HarnessExecutor e = executor(script);
        e.initialize();
        e.executeWorkflow("Main", Map.of());
        assertThat(Files.readString(dir.resolve("work/out.txt"))).contains("a b;c").doesNotContain("ignored");
    }
}
