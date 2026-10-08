package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.eval.assertions.WorkflowAssertions;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.evalreport.loom.LoomTrace;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.prompt.PromptSettings;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pattern chapter 8 teaches does what it says: prompt files are found, a reply sequence advances, and the current-task matcher ignores history. */
class TrajectoryExampleBehavesTest {

    @TempDir Path dir;

    private WorkflowTrace run(ScriptedClient model) throws Exception {
        Path script = dir.resolve("main.loom");
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/writer.md"), "---\ndescription: w\n---\nYou write.\n");
        Files.writeString(dir.resolve("prompts/reviewer.md"), "---\ndescription: r\n---\nYou review.\n");
        Files.writeString(script, """
                agent Writer { model: "m" prompt: "writer" }
                agent Reviewer { model: "m" prompt: "reviewer" }
                workflow Main(topic) {
                    delegate "Write about {topic}" to Writer -> draft
                    delegate "Review: {draft}" to Reviewer -> verdict expecting { verdict: enum["OK", "REDO"] }
                    alt (verdict.verdict == "REDO") {
                        delegate "Write again about {topic}" to Writer -> draft2
                    }
                    note "done"
                }
                """);
        LoomScript loaded = new LoomLoader().load(script.toString());
        HarnessExecutor executor = new HarnessExecutor(loaded, new ToolRegistry(), name -> model);
        executor.setHumanInterface(q -> "yes");
        executor.setBaseDir(dir);
        executor.setEnvLookup(n -> "test");
        executor.setPromptCatalog(PromptSupport.catalog(loaded, script, PromptSettings.NONE));
        WorkflowDef main = loaded.getWorkflows().stream().filter(w -> w.getName().equals("Main")).findFirst().orElseThrow();
        LoomTrace trace = LoomTrace.attach(executor).workflow(main);
        executor.initialize();
        try {
            executor.executeWorkflow("Main", Map.of("topic", "composting"));
        } finally {
            executor.shutdown();
        }
        return trace.finish(executor.spend());
    }

    @Test
    void theReviewerSendsTheWriterBackOnceBecauseTheReplySequenceAdvances() throws Exception {
        ScriptedClient model = new ScriptedClient()
                .whenTaskSeen("Review:", ScriptedClient.reactFinal("{\"verdict\": \"REDO\"}"))
                .whenSeenThen("Write", ScriptedClient.reactFinal("draft"), ScriptedClient.reactFinal("better draft"))
                .otherwise(ScriptedClient.reactFinal("ok"));

        WorkflowTrace t = run(model);

        WorkflowAssertions.assertThat(t).invokesAgents("Writer", "Reviewer").delegatesToTimes("Writer", 2).delegatesToTimes("Reviewer", 1);
        assertThat(model.requests()).anyMatch(r -> r.contains("Current Task:"));
    }

    @Test
    void aReviewerTaskIsNotMistakenForTheWritersBecauseOfHistory() throws Exception {
        // the Reviewer's request carries the Writer's task ("Write about ...") in its history; whenSeen("Write") would answer the Reviewer with the writer's rule
        ScriptedClient model = new ScriptedClient()
                .whenTaskSeen("Review:", ScriptedClient.reactFinal("{\"verdict\": \"OK\"}"))
                .whenTaskSeen("Write", ScriptedClient.reactFinal("draft"))
                .otherwise(ScriptedClient.reactFinal("ok"));

        WorkflowAssertions.assertThat(run(model)).delegatesToTimes("Writer", 1);   // OK: the second write never happens
    }
}
