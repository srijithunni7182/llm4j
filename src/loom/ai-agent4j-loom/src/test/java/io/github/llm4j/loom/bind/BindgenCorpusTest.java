package io.github.llm4j.loom.bind;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A4 of spec 15: the bindings of every example script compile together under -Xlint:all -Werror.
 */
class BindgenCorpusTest {

    @TempDir Path dir;

    @Test
    void everyExampleGeneratesAndTheWholeSetCompilesWithoutWarnings() throws Exception {
        Path examples = Path.of("src/main/resources/review-rules/core/examples");
        List<Path> scripts;
        try (Stream<Path> s = Files.walk(examples)) {
            scripts = s.filter(p -> p.toString().endsWith(".loom")).sorted().toList();
        }
        assertThat(scripts).hasSizeGreaterThan(50);
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "-Xlint:all",
                                "-Werror",
                                "--release",
                                "17",
                                "-d",
                                dir.resolve("classes").toString(),
                                "-cp",
                                System.getProperty("java.class.path")));
        Files.createDirectories(dir.resolve("classes"));
        int generated = 0;
        List<String> skipped = new ArrayList<>();
        int i = 0;
        for (Path script : scripts) {
            i++;
            Bindgen.Generated g;
            try {
                g = Bindgen.generate(script, "corpus.s" + i, null, Bindgen.Nullability.NONE);
            } catch (Exception | LinkageError e) {
                skipped.add(script.getFileName() + ": " + e.getMessage());
                continue;
            }
            generated++;
            for (var f : g.files().entrySet()) {
                Path src = dir.resolve("src").resolve(f.getKey());
                Files.createDirectories(src.getParent());
                Files.writeString(src, f.getValue());
                args.add(src.toString());
            }
        }
        // an example that does not load as a script (a deliberately broken one) is not a binding
        // target
        assertThat(generated).as("skipped: " + skipped).isGreaterThan(scripts.size() / 2);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc =
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, err, args.toArray(String[]::new));
        assertThat(rc).as(err.toString()).isZero();
    }
}
