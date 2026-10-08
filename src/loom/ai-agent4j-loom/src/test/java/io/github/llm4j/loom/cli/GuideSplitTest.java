package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.execution.TraceEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chapter 6 tells an agent to split a long script into an entry file, an agents file and one file per sub-workflow. This writes the guide's
 * own example to disk and runs it, and checks the claims the advice rests on.
 */
class GuideSplitTest {

    private static final String CHAPTER = "../../../docs/guide/06-build-the-workflow.md";

    private static Path writeExample(Path dir) throws Exception {
        Matcher m = Pattern.compile("```loom file=split/(\\S+)\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(Path.of(CHAPTER)));
        Map<String, String> files = new LinkedHashMap<>();
        while (m.find()) files.put(m.group(1), m.group(2));
        assertThat(files).containsOnlyKeys("main.loom", "agents/team.loom", "flows/review.loom");
        for (var f : files.entrySet()) {
            Path p = dir.resolve(f.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
            assertThat(f.getValue().lines().count()).as(f.getKey() + " is short").isLessThan(60);
        }
        return dir.resolve("main.loom");
    }

    @Test
    void theSplitExampleChecksCleanAndRunsWithTheCallReturningResult(@TempDir Path dir) throws Exception {
        Path main = writeExample(dir);

        var out = new java.io.ByteArrayOutputStream();
        var old = System.out;
        System.setOut(new java.io.PrintStream(out, true));
        int code;
        try {
            code = WeaveCLI.commandLine().execute("check", main.toString(), "--no-env", "--strict");
        } finally {
            System.setOut(old);
        }
        assertThat(code).as(out.toString()).isZero();

        List<TraceEvent> trace = new ArrayList<>();
        HarnessExecutor e = new HarnessExecutor(new LoomLoader().load(main.toString()), new ToolRegistry(), new MockModels());
        e.addTraceListener(trace::add);
        e.setBaseDir(dir);
        try {
            e.initialize();
            e.executeWorkflow("Main", Map.of("topic", "composting"));
        } finally {
            e.shutdown();
        }
        assertThat(trace).filteredOn(t -> t.type().equals(TraceEvent.NOTE)).extracting(TraceEvent::text).singleElement().asString().startsWith("Ready: [mock answer]");
        assertThat(trace).filteredOn(t -> t.type().equals(TraceEvent.DELEGATE_START)).extracting(TraceEvent::agent).contains("Writer", "Editor");
    }

    @Test
    void theGraphFollowsTheImportsAndDrawsTheCalledWorkflowSeparately(@TempDir Path dir) throws Exception {
        Path main = writeExample(dir);
        var result = new io.github.llm4j.loom.graph.GraphService().graph(main);
        assertThat(result.workflows()).extracting(w -> w.name()).contains("Main", "ReviewLoop");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void aBudgetInAnImportedFileBeatsTheEntryFilesSoTheGuideSaysToKeepItInTheEntryFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lib.loom"), "budget { tokens: 111  calls: 5 }\nagent A { model: \"m\" system: \"s\" }\n");
        Files.writeString(dir.resolve("main.loom"), "import \"lib.loom\"\nbudget { tokens: 999  calls: 9 }\nworkflow Main() { note \"x\" }\n");

        var script = new LoomLoader().load(dir.resolve("main.loom").toString());

        assertThat(script.getBudget().getTokens()).isEqualTo(111L);
        assertThat(Files.readString(Path.of(CHAPTER))).contains("`budget`, `audit` and `rate_limits` go in the entry file only");
    }

    @Test
    void twoFilesDefiningTheSameAgentNameDoNotFailSoTheGuideSaysToKeepNamesUnique(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.loom"), "agent Writer { model: \"m1\" system: \"s\" }\n");
        Files.writeString(dir.resolve("main.loom"), "import \"a.loom\"\nagent Writer { model: \"m2\" system: \"s\" }\nworkflow Main() { note \"x\" }\n");

        var script = new LoomLoader().load(dir.resolve("main.loom").toString());

        assertThat(script.getAgents().stream().filter(a -> a.getName().equals("Writer")).count()).isEqualTo(2);
        assertThat(Files.readString(Path.of(CHAPTER))).contains("two files defining the same name do not fail");
    }
}
