package io.github.llm4j.hexamind.eval;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.eval.export.WorkflowTrace;
import io.github.llm4j.evalreport.loom.LoomTrace;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.WorkflowDef;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs {@code eval/hexamind.loom} through Loom's own executor and captures the trace for the report. The
 * script's {@code tool Search} (SerpAPI) is replaced by recorded search: a query about a fabricated term
 * finds nothing, any other query is answered from the recorded library, so a debate costs only model tokens.
 */
public final class LoomDebateRunner {

    /** What a debate produced. */
    public record Debate(WorkflowTrace trace, Map<String, String> variables, String output, String stopped) {}

    static final Path SCRIPT = Path.of("eval", "hexamind.loom");

    private LoomDebateRunner() {}

    /** The script text with the declared Search tool removed, so a registered tool of that name is used. */
    static String scriptText(java.util.function.UnaryOperator<String> edit) {
        try {
            return edit.apply(Files.readString(SCRIPT)).replaceAll("(?s)tool Search \\{.*?\\}\\s*", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Recorded search: nothing for the fabricated terms, the recorded library for everything else. */
    public static Tool search(List<String> fabricatedTerms) {
        return SearchLibrary.load().tool("Search", List.of(), fabricatedTerms);
    }

    /**
     * Runs one workflow of the script with {@code vars}, reading {@code outputVar} afterwards (the name
     * the workflow hands off).
     */
    public static Debate run(
            String workflow,
            Map<String, String> vars,
            String outputVar,
            List<String> expectedPath,
            List<String> fabricatedTerms,
            LLMClientFactory models) {
        return run(workflow, vars, outputVar, expectedPath, fabricatedTerms, models, x -> x);
    }

    /** As {@link #run}, with an edit applied to the script text first (tests shrink the budget this way). */
    public static Debate run(
            String workflow,
            Map<String, String> vars,
            String outputVar,
            List<String> expectedPath,
            List<String> fabricatedTerms,
            LLMClientFactory models,
            java.util.function.UnaryOperator<String> edit) {
        LoomScript parsed = new LoomParser(new Lexer(scriptText(edit)).tokenize()).parseScript();
        ToolRegistry tools = new ToolRegistry();
        tools.register("Search", search(fabricatedTerms));
        HarnessExecutor e = new HarnessExecutor(parsed, tools, models);
        LoomTrace trace = LoomTrace.attach(e);
        WorkflowDef def =
                parsed.getWorkflows().stream()
                        .filter(w -> w.getName().equals(workflow))
                        .findFirst()
                        .orElseThrow();
        trace.workflow(def).expectPath(expectedPath.toArray(String[]::new));
        e.initialize();
        String stopped = null;
        try {
            e.executeWorkflow(workflow, vars);
        } catch (io.github.llm4j.budget.BudgetExceeded b) {
            stopped = b.getMessage(); // Loom's budget is a hard stop: no partial consensus is produced
        }
        Object out = e.getContext().getVariable(outputVar);
        return new Debate(trace.finish(null), vars, out == null ? null : String.valueOf(out), stopped);
    }

    /** Node ids of the script's main paths (see {@code TrajectoryPathTest}). */
    public static final List<String> FULL = List.of("start", "n2", "n4", "n5", "n8", "n9", "n10", "n11", "n12", "end");

    public static final List<String> DEBUNK = List.of("start", "n2", "n4", "n5", "n7", "end");
}
