package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
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
 * Phase 35, D16 rule 4: a tool that holds something open (a browser) is closed when the run ends,
 * however it ends, and the next run builds it again.
 */
class ToolsCloseAtRunEndTest {

    @TempDir Path dir;
    final List<String> events = new ArrayList<>();

    final class Held implements Tool, AutoCloseable {
        final int n;

        Held(int n) {
            this.n = n;
        }

        public String getName() {
            return "Held";
        }

        public String getDescription() {
            return "holds something open";
        }

        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        public String execute(Map<String, Object> args) {
            events.add("used " + n);
            if (Boolean.parseBoolean(String.valueOf(args.get("fail")))) throw new IllegalStateException("boom");
            return "ok";
        }

        public void close() {
            events.add("closed " + n);
        }
    }

    HarnessExecutor executor(String workflow) throws Exception {
        Path file = Files.writeString(dir.resolve("main.loom"), "tool H { use: holder }\n" + workflow);
        HarnessExecutor e = new HarnessExecutor(new LoomLoader().load(file.toString()), new ToolRegistry(), m -> {
            throw new IllegalStateException();
        });
        e.setBaseDir(dir);
        e.setEnvLookup(k -> null);
        int[] built = {0};
        e.addToolKind(new ToolKind() {
            public String name() {
                return "holder";
            }

            public Tool create(String name, Map<String, String> options, Path baseDir) {
                return new Held(++built[0]);
            }
        });
        e.initialize();
        return e;
    }

    @Test
    void aHeldToolIsClosedAtTheEndOfEachRunAndBuiltAgainForTheNext() throws Exception {
        HarnessExecutor e = executor("workflow Main() { use H() -> a\n use H() -> b }\nworkflow Again() { use H() -> c }");
        e.executeWorkflow("Main", Map.of());
        e.executeWorkflow("Again", Map.of());
        assertThat(events).containsExactly("used 1", "used 1", "closed 1", "used 2", "closed 2");
    }

    @Test
    void aRunThatFailsClosesItToo() throws Exception {
        HarnessExecutor e = executor("workflow Main() { use H(fail = \"true\") -> a }");
        assertThatThrownBy(() -> e.executeWorkflow("Main", Map.of()));
        assertThat(events).last().isEqualTo("closed 1");
    }
}
