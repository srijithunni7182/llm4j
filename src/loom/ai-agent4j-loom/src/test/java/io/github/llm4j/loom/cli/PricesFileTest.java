package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a run finds its prices when nobody passes --prices, so daemon, tick and resume are priced
 * too.
 */
class PricesFileTest {

    @TempDir Path dir;

    private WeaveEnv env(Map<String, String> vars) {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream());
        return new WeaveEnv(
                m -> null,
                message -> "yes",
                sink,
                sink,
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"),
                vars::get);
    }

    private RunSpec spec(String prices) {
        return new RunSpec(
                dir.resolve("flow.loom").toString(),
                null,
                "Main",
                Map.of(),
                null,
                null,
                null,
                prices,
                null);
    }

    @Test
    void
            anExplicitFileWins_thenAFileNamedForTheScript_thenPricesPropertiesBesideIt_thenTheEnvironment()
                    throws Exception {
        File script = dir.resolve("flow.loom").toFile();
        assertThat(Runs.pricesFile(spec(null), script, env(Map.of())))
                .isNull(); // nothing anywhere: cost is not reported

        Path fromEnv = Files.writeString(dir.resolve("elsewhere.txt"), "m = 1 / 2\n");
        assertThat(
                        Runs.pricesFile(
                                spec(null), script, env(Map.of("LOOM_PRICES", fromEnv.toString()))))
                .isEqualTo(fromEnv);

        Path shared = Files.writeString(dir.resolve("prices.properties"), "m = 1 / 2\n");
        assertThat(
                        Runs.pricesFile(
                                spec(null), script, env(Map.of("LOOM_PRICES", fromEnv.toString()))))
                .isEqualTo(shared);

        Path named = Files.writeString(dir.resolve("flow.prices"), "m = 3 / 4\n");
        assertThat(Runs.pricesFile(spec(null), script, env(Map.of()))).isEqualTo(named);

        assertThat(Runs.pricesFile(spec("given.properties"), script, env(Map.of())))
                .isEqualTo(Path.of("given.properties"));
    }
}
