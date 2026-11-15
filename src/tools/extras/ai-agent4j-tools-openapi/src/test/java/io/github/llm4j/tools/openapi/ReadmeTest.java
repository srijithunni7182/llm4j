package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The README's samples are true: each declaration is accepted, and its options table is the kind's. */
class ReadmeTest {

    static final String README = read();

    static String read() {
        try {
            return Files.readString(Path.of("README.md"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everyDeclarationInTheScriptSampleIsAccepted() {
        Matcher tool = Pattern.compile("tool (\\w+) \\{ use: openapi(.*?)\\}").matcher(README);
        int seen = 0;
        while (tool.find()) {
            seen++;
            Map<String, String> options = new LinkedHashMap<>();
            Matcher pair = Pattern.compile("(\\w+): \"?([^\"\\s]+)\"?").matcher(tool.group(2));
            while (pair.find()) if (!pair.group(1).equals("effect")) options.put(pair.group(1), pair.group(2));
            assertThat(options).containsKeys("spec", "operation");
            assertThat(new OpenApiKind().check(options, Path.of("."))).as(tool.group()).isNull();
        }
        assertThat(seen).isEqualTo(2);
    }

    @Test
    void theOptionsTableListsExactlyTheKindsOptions() {
        OpenApiKind kind = new OpenApiKind();
        Set<String> listed = new TreeSet<>();
        Matcher row =
                Pattern.compile("(?m)^\\| `(\\w+)` \\|").matcher(README.substring(README.indexOf("| Option |")));
        while (row.find()) listed.add(row.group(1));
        Set<String> all = new TreeSet<>(kind.required());
        all.addAll(kind.optional());
        assertThat(listed).isEqualTo(all);
    }

    @Test
    void theJavaSampleUsesOptionsTheKindHas() {
        Matcher quoted = Pattern.compile("\"(spec|operation|base_url)\"").matcher(README);
        Set<String> used = new TreeSet<>();
        while (quoted.find()) used.add(quoted.group(1));
        OpenApiKind kind = new OpenApiKind();
        Set<String> known = new TreeSet<>(kind.required());
        known.addAll(kind.optional());
        assertThat(known).containsAll(used);
    }
}
