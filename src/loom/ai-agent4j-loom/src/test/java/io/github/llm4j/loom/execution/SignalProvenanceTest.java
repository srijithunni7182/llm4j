package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.children.ChildHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guard G22 of phase 34 (D14), made direct: the provenance oracle sweeps every script of the
 * repository but swallows a run that fails, so this runs the two signal scripts of the kit with the
 * oracle's own seam and checks that a model-written word crosses a signal in each direction and is
 * <em>still called model-written</em> at every exit it reaches. A negative control shows the seam
 * does see an exit that is not model text.
 */
class SignalProvenanceTest {

    private static final Path CTK = Path.of("..", "ctk", "scripts").toAbsolutePath().normalize();

    private record Exit(String sink, String value, boolean modelWritten) {}

    private static List<Exit> run(String script) throws Exception {
        ChildHarness h = new ChildHarness();
        List<Exit> exits = new ArrayList<>();
        HarnessExecutor e = h.parent(Files.readString(CTK.resolve(script)), h.controlled());
        e.setSinkOracle((sink, value, modelWritten) -> exits.add(new Exit(sink, String.valueOf(value), modelWritten)));
        e.executeWorkflow("main", Map.of());
        return exits;
    }

    @Test
    void aModelWrittenTextSentToAChildIsStillModelWrittenWhereTheChildReturnsItAndPromptsWithIt() throws Exception {
        List<Exit> exits = run("signal_to_child.loom");

        List<Exit> carrying = exits.stream().filter(x -> x.value().contains("mock")).toList();
        assertThat(carrying).as("the canary crossed the signal and reached exits").hasSizeGreaterThanOrEqualTo(2);
        assertThat(carrying).allMatch(Exit::modelWritten);
        assertThat(exits).anyMatch(x -> x.sink().contains("notify"));
    }

    @Test
    void aModelWrittenTextSentToTheParentIsStillModelWrittenWhereTheParentReturnsIt() throws Exception {
        List<Exit> exits = run("signal_to_parent.loom");

        List<Exit> carrying = exits.stream().filter(x -> x.value().contains("mock")).toList();
        assertThat(carrying).hasSizeGreaterThanOrEqualTo(2);
        assertThat(carrying).allMatch(Exit::modelWritten);
        assertThat(exits).anyMatch(x -> x.sink().equals("return") && x.value().contains("mock"));
    }

    @Test
    void aStickyAndAMessageSignalKeepTheirTextAndTheScriptRunsToTheEnd() throws Exception {
        List<Exit> exits = run("signal_sticky.loom");

        assertThat(exits).anyMatch(x -> x.sink().equals("return") && x.value().contains("yes yes m1 m2"));
    }

    @Test
    void negativeControlAPlainScriptTextIsNotCalledModelWritten() throws Exception {
        ChildHarness h = new ChildHarness();
        List<Exit> exits = new ArrayList<>();
        HarnessExecutor e = h.parent("workflow main() { return \"plain words\" }", h.controlled());
        e.setSinkOracle((sink, value, modelWritten) -> exits.add(new Exit(sink, String.valueOf(value), modelWritten)));
        e.executeWorkflow("main", Map.of());

        assertThat(exits).anyMatch(x -> x.value().equals("plain words") && !x.modelWritten());
    }
}
