package io.github.llm4j.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The README's samples are true (D4 rule 3): the script's tool line is a declaration the kind
 * accepts, and its options table lists exactly the kind's options.
 */
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
    void theScriptsDeclarationIsAccepted() {
        Matcher m = Pattern.compile("tool Web \\{ use: search(.*?)\\}").matcher(README);
        assertThat(m.find()).isTrue();
        Map<String, String> options = new LinkedHashMap<>();
        Matcher pair = Pattern.compile("(\\w+): (\\S+)").matcher(m.group(1));
        while (pair.find()) options.put(pair.group(1), pair.group(2).startsWith("secret.") ? "test-key-0000" : pair.group(2));
        assertThat(options).containsKeys("provider", "api_key");
        assertThat(new SearchKind().check(options, Path.of("."))).isNull();
    }

    @Test
    void theOptionsTableListsTheKindsOptions() {
        SearchKind kind = new SearchKind();
        java.util.Set<String> listed = new java.util.TreeSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `(\\w+)` \\|").matcher(README.substring(README.indexOf("| Option |")));
        while (row.find()) listed.add(row.group(1));
        java.util.Set<String> all = new java.util.TreeSet<>(kind.required());
        all.addAll(kind.optional());
        assertThat(listed).containsAll(all);
    }
}
