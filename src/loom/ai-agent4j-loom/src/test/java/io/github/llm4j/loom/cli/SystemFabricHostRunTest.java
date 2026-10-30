package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A command that does not end is stopped at the limit, and what it said is kept. */
class SystemFabricHostRunTest {

    /** A program that says something and then waits far longer than any limit in these tests. */
    public static final class Hangs {
        public static void main(String[] args) throws Exception {
            System.out.println("waiting for the master key");
            System.out.flush();
            Thread.sleep(600_000);
        }
    }

    /** A program that answers at once. */
    public static final class Answers {
        public static void main(String[] args) {
            System.out.println("done");
        }
    }

    private static List<String> java(Class<?> main) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                main.getName());
    }

    private SystemFabricHost host(long limitSeconds) {
        return new SystemFabricHost(name -> null, Path.of("."), Path.of("."), limitSeconds);
    }

    @Test
    @Timeout(60)
    void stopsACommandThatDoesNotEndAndKeepsWhatItSaid() {
        var result = host(1).run(java(Hangs.class), Map.of());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output())
                .contains("waiting for the master key")
                .contains("stopped after 1 seconds");
    }

    @Test
    @Timeout(60)
    void returnsTheExitAndOutputOfACommandThatEnds() {
        var result = host(30).run(java(Answers.class), Map.of());

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("done");
    }
}
