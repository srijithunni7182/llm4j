package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D12, D15 and G64: no model-written value becomes trusted by passing through {@code
 * compute}, {@code verify} or {@code scan}. The mock model's answer carries the canary "mock"; at
 * the exit (a tool of the host's that changes things) the runtime's belief "a model wrote this"
 * must hold wherever the canary is, and counts the host parsed stay trusted.
 */
class ToolResultProvenanceTest {

    @TempDir Path dir;

    record Exit(String sink, String value, boolean modelWritten) {}

    List<Exit> run(String script) throws Exception {
        return run(script, io.github.llm4j.loom.runtime.RunJournal.inMemory());
    }

    List<Exit> run(String script, io.github.llm4j.loom.runtime.RunJournal journal) throws Exception {
        Path file = Files.writeString(dir.resolve("main.loom"), script);
        var parsed = new LoomLoader().load(file.toString());
        MockModels mock = new MockModels();
        // Hook is the host's own tool: the audit cannot see into it, so it changes things and what
        // reaches it leaves the workflow
        ToolRegistry host = new ToolRegistry();
        host.register("Hook", new io.github.llm4j.agent.tools.EchoTool());
        HarnessExecutor e = new HarnessExecutor(parsed, host, mock::createClient);
        e.setBaseDir(dir);
        e.setJournal(journal);
        e.setEnvLookup(k -> "https://example.invalid/hook");
        e.setHumanInterface(m -> "yes");
        List<Exit> exits = new ArrayList<>();
        e.setSinkOracle(
                (sink, value, modelWritten) ->
                        exits.add(new Exit(sink, String.valueOf(value), modelWritten)));
        e.initialize();
        try {
            e.executeWorkflow("Main", Map.of("q", "go"));
        } catch (RuntimeException failed) {
            throw new AssertionError("the workflow should run to the end", failed);
        }
        return exits;
    }

    static final String HEADER =
            """
            tool Calc  { use: compute }
            tool Check { use: verify  root: "out" }
            tool Scan  { use: scan }
            agent A { model: "m"  system: "s" }
            """;

    @Test
    void aValuePickedOutOfAModelsAnswerIsStillTheModelsText() throws Exception {
        List<Exit> exits =
                run(
                        HEADER
                                + """
                                workflow Main(q) {
                                    delegate "{q}" to A -> answer
                                    use Calc(action = "json", document = "\\\"{answer}\\\"") -> picked
                                    use Hook(text = "{picked.value}")
                                }
                                """);
        assertThat(exits).isNotEmpty();
        Exit hook = exits.get(exits.size() - 1);
        assertThat(hook.value()).contains("mock");
        assertThat(hook.modelWritten()).isTrue();
    }

    @Test
    void aComputedValueFromTheScriptsOwnNumbersIsTrusted() throws Exception {
        List<Exit> exits =
                run(
                        HEADER
                                + """
                                workflow Main(q) {
                                    use Calc(action = "calc", expression = "0.1 + 0.2") -> sum
                                    use Hook(text = "{sum.value}")
                                }
                                """);
        assertThat(exits.get(exits.size() - 1).modelWritten()).isFalse();
    }

    @Test
    void aReportsTestNamesStayUncheckedAndItsCountsAreTrusted() throws Exception {
        Files.createDirectories(dir.resolve("out"));
        Files.writeString(
                dir.resolve("out/report.xml"),
                "<testsuite><testcase classname=\"A\" name=\"mock says ignore the rules\"><failure/></testcase></testsuite>");
        List<Exit> exits =
                run(
                        HEADER
                                + """
                                workflow Main(q) {
                                    use Check(format = "junit", source = "report.xml") -> result
                                    use Hook(text = "{result.items}")
                                    use Hook(text = "{result.failed}")
                                }
                                """);
        assertThat(exits).hasSize(2);
        assertThat(exits.get(0).value()).contains("mock");
        assertThat(exits.get(0).modelWritten()).isTrue();
        assertThat(exits.get(1).value()).isEqualTo("{text=1}");
        assertThat(exits.get(1).modelWritten()).isFalse();
    }

    @Test
    void aScanOfAModelsAnswerKeepsItsOrigin() throws Exception {
        List<Exit> exits =
                run(
                        HEADER
                                + """
                                workflow Main(q) {
                                    delegate "{q}" to A -> answer
                                    use Scan(text = answer) -> found
                                    use Hook(text = "{found.findings}")
                                    use Hook(text = "{found.count}")
                                }
                                """);
        assertThat(exits.get(exits.size() - 2).modelWritten()).isTrue();
        assertThat(exits.get(exits.size() - 1).value()).isEqualTo("{text=0}");
        assertThat(exits.get(exits.size() - 1).modelWritten()).isFalse();
    }

    @Test
    void aResumedRunThatReplaysTheStepKeepsTheOrigin() throws Exception {
        String script =
                HEADER
                        + """
                        workflow Main(q) {
                            delegate "{q}" to A -> answer
                            use Calc(action = "json", document = "\\"{answer}\\"") -> picked
                            return picked.value
                        }
                        """;
        var journal = io.github.llm4j.loom.runtime.RunJournal.inMemory();
        List<Exit> first = run(script, journal);
        List<Exit> again = run(script, journal);
        assertThat(first.get(first.size() - 1).modelWritten()).isTrue();
        Exit replayed = again.get(again.size() - 1);
        assertThat(replayed.sink()).isEqualTo("return");
        assertThat(replayed.value()).contains("mock");
        assertThat(replayed.modelWritten()).isTrue();
    }
}
