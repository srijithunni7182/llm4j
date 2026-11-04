package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 26c, R15: a route that sets a variable must say where its value came from. The old form,
 * {@code setVariable(name, value)}, is not used by execution code: it goes through {@code bind},
 * which requires a provenance. The only direct calls left are the implementation of {@code bind}
 * itself, the call frame (which copies the origin by hand), and a scratch scope in StopHandlers that
 * is not the run's.
 */
class ProvenanceRoutesTest {

    @Test
    void noExecutionClassSetsAVariableWithoutSayingWhereItCameFrom() throws Exception {
        Path root = Path.of("src/main/java/io/github/llm4j/loom/execution");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains("setVariable(") || line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
                    boolean allowed =
                            name.equals("StopHandlers.java")
                                    || (name.equals("HarnessExecutor.java")
                                            && (line.contains("context.setVariable(name, value);")
                                                    || line.contains("context.setVariable(base, map);")
                                                    || line.contains("subContext.setVariable(arg.getKey()")
                                                    || line.contains("original.setVariable(call.getResultVariable()")));
                    if (!allowed) offenders.add(name + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        assertThat(offenders).as("setVariable without a provenance").isEmpty();
    }

    @Test
    void everyDirectSetOfAVariableInTheCallFrameIsFollowedByItsOrigin() throws Exception {
        String src = Files.readString(Path.of("src/main/java/io/github/llm4j/loom/execution/HarnessExecutor.java"));
        assertThat(src).contains("subContext.setOrigin(arg.getKey(), value.tag());");
        assertThat(src).contains("original.setOrigin(");
        // the map form (results[key]) joins the origin of every answer it holds
        assertThat(src).contains("context.setOrigin(base, joined);");
    }
}
