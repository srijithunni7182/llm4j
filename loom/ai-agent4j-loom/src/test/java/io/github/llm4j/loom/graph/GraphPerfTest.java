package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** VP.1, VP.2: how long drawing takes. A breach is a failure. */
class GraphPerfTest {

    /** Over 400 statements in nested blocks: 10 rounds of an alt with a loop, delegates and notes inside. */
    static String largeScript() {
        StringBuilder s = new StringBuilder("agent A { model: \"m\" }\nworkflow Big() {\n");
        for (int round = 0; round < 10; round++) {
            s.append("  alt (x == \"").append(round).append("\") {\n");
            for (int i = 0; i < 15; i++) {
                s.append("    delegate \"step ").append(i).append("\" to A -> v").append(i).append('\n');
            }
            s.append("    loop until (done == \"yes\") max 3 {\n");
            for (int i = 0; i < 15; i++) {
                s.append("      note \"inside ").append(i).append("\"\n");
            }
            s.append("    }\n  } else {\n");
            for (int i = 0; i < 15; i++) {
                s.append("    note \"else ").append(i).append("\"\n");
            }
            s.append("  }\n");
        }
        return s.append("}\n").toString();
    }

    @Test
    void aWorkflowOfFourHundredStatementsIsBuiltInUnderTwoHundredMilliseconds() {
        LoomScript script = GraphTestSupport.parse(largeScript());
        GraphBuilder builder = new GraphBuilder();
        var workflow = script.getWorkflows().get(0);
        WorkflowGraph warm = builder.build(workflow, "big.loom");
        assertThat(warm.nodes().size()).isGreaterThan(400);

        long worst = 0;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            builder.build(workflow, "big.loom");
            worst = Math.max(worst, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        assertThat(worst).as("worst of three, ms").isLessThanOrEqualTo(200);
    }

    @Test
    void weaveGraphOnTheContentFactoryFinishesInUnderThreeSecondsFromAColdJvm() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long worst = 0;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    "io.github.llm4j.loom.cli.WeaveCLI", "graph", "samples/content_factory/main.loom")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(new File(System.getProperty("java.io.tmpdir"), "graph-perf.out")))
                    .start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            worst = Math.max(worst, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        assertThat(worst).as("worst of three, ms").isLessThanOrEqualTo(3000);
    }
}
