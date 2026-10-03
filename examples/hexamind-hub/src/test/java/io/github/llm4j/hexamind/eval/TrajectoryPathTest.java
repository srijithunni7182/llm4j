package io.github.llm4j.hexamind.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.evalreport.loom.LoomTrace;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Runs hexamind.loom on a scripted model and checks which way the debate went. Free and offline. */
class TrajectoryPathTest {

    private static String script() throws Exception {
        String s = Files.readString(Path.of("eval/hexamind.loom"));
        // the real run searches over the network; here the declared tool is replaced by a fixture
        return s.replaceAll("(?s)tool Search \\{.*?\\}\\s*", "");
    }

    private WorkflowTrace run(ScriptedModel model, String problem) throws Exception {
        var parsed = new LoomParser(new Lexer(script()).tokenize()).parseScript();
        ToolRegistry tools = new ToolRegistry();
        tools.register("Search", new FixtureSearchTool(List.of("a fixture snippet")));
        HarnessExecutor e = new HarnessExecutor(parsed, tools, model);
        LoomTrace trace = LoomTrace.attach(e);
        trace.workflow(parsed.getWorkflows().stream()
                .filter(w -> w.getName().equals("Collaborate"))
                .findFirst()
                .orElseThrow());
        e.initialize();
        e.executeWorkflow("Collaborate", Map.of("problem", problem));
        return trace.finish(null);
    }

    @Test
    void fabricatedPremiseTakesTheDebunkBranch() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .whenSeen("Round 1 findings", "It does not exist.")
                .whenSeen("Alex: ", "{\"fabricated\": \"YES\", \"term\": \"Quantum Flux\"}");
        WorkflowTrace t = run(model, "Explain the Quantum Flux protocol");
        assertThat(t.actualPath()).containsExactly("start", "n2", "n4", "n5", "n7", "end");
    }

    @Test
    void realPremiseRunsAllFiveRoundsThenTheCoordinator() throws Exception {
        ScriptedModel model = new ScriptedModel()
                .whenSeen("Alex: ", "{\"fabricated\": \"NO\", \"term\": \"\"}");
        WorkflowTrace t = run(model, "Should we adopt Kubernetes?");
        assertThat(t.actualPath())
                .containsExactly(
                        "start", "n2", "n4", "n5", "n8", "n9", "n10", "n11", "n12", "end");
    }
}
