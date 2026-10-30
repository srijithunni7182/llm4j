package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code weave probe eviction}: the context manager's eviction on a small window, with no model. */
class ProbeCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int run(String... args) {
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                        List.of("weave"),
                        k -> null);
        ProbeCommand.Eviction c = new ProbeCommand.Eviction();
        new picocli.CommandLine(c).parseArgs(args);
        return ProbeCommand.Eviction.probe(c, env);
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aMechanismThatBehavesExitsZeroAndSaysWhatItDid() {
        assertThat(run("--window", "4000", "--turn-chars", "800")).isZero();

        assertThat(said()).contains("evicted at turn").contains("oldest first").contains("the model was told").contains("task kept");
    }

    @Test
    void aProbeThatTestedNoEvictionExitsOneNamingWhy() {
        assertThat(run("--window", "1000000")).isEqualTo(1);

        assertThat(said()).contains("never evicted");
    }

    @Test
    void aStrategyThatNeedsAModelOrAnUnknownOneIsAProblemWithTheOptions() {
        assertThat(run("--strategy", "summarize")).isEqualTo(2);
        assertThat(said()).contains("needs a model");
        assertThat(run("--strategy", "sideways")).isEqualTo(2);
    }

    @Test
    void anOptionOutOfRangeIsAProblemWithTheOptions() {
        assertThat(run("--window", "10")).isEqualTo(2);
        assertThat(said()).contains("window");
    }
}
