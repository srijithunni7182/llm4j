package io.github.llm4j.evalreport.loom;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.loom.ast.DelegateStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R4.4 of loom-prompt-files: the report shows the prompt id and version on an agent's steps. */
class ReportPromptTest {

    @TempDir Path dir;

    private static WorkflowDef workflow() {
        WorkflowDef w = new WorkflowDef("Main");
        w.addStatement(new DelegateStmt("go", "Researcher", "a"));
        w.addStatement(new DelegateStmt("then", "Plain", "b"));
        return w;
    }

    private static WorkflowTrace.Node step(WorkflowGraph g, String agent) {
        return g.nodes().stream().filter(n -> agent.equals(n.agent())).findFirst().orElseThrow();
    }

    @Test
    void aStepOfAnAgentWithAPromptCarriesIt() {
        WorkflowGraph g = WorkflowGraph.of(workflow(), agent -> agent.equals("Researcher") ? "researcher@v2" : null);

        assertThat(step(g, "Researcher").attrs()).containsEntry("prompt", "researcher@v2");
        assertThat(step(g, "Plain").attrs()).doesNotContainKey("prompt");
    }

    @Test
    void withoutLabelsTheGraphIsWhatItWas() {
        assertThat(WorkflowGraph.of(workflow()).nodes()).isEqualTo(WorkflowGraph.of(workflow(), agent -> null).nodes());
    }

    @Test
    void anAttachedExecutorSuppliesTheLabelsOfTheAgentsItRuns() throws IOException {
        Path file = dir.resolve("prompts/researcher/v1.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "one");
        Files.writeString(dir.resolve("prompts/researcher/v2.md"), "two");
        LoomScript script = new LoomParser(new Lexer("""
                agent Researcher { model: "m" prompt: "researcher" }
                agent Plain { model: "m" system: "s" }
                workflow Main() {
                    delegate "go" to Researcher -> a
                    delegate "then" to Plain -> b
                }
                """).tokenize()).parseScript();
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> { throw new IllegalStateException("no models"); });
        executor.setPromptCatalog(new PromptCatalog(new MarkdownFolderPromptRegistry(dir.resolve("prompts")), Map.of("researcher", "v1")));

        LoomTrace trace = LoomTrace.attach(executor).workflow(script.getWorkflows().get(0));

        assertThat(step(trace.graph(), "Researcher").attrs()).containsEntry("prompt", "researcher@v1");
        assertThat(step(trace.graph(), "Plain").attrs()).doesNotContainKey("prompt");
    }
}
