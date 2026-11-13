package io.github.llm4j.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The light tools module stays light (phase 35, D4 rule 2): what an application gets by depending
 * on it is the core, Jackson and, only if it asks, the mail library. Maven writes the module's
 * resolved direct dependencies to {@code target/weight/dependencies.txt} before the tests run (the
 * {@code weight-rule} execution in the tools parent); this test pins that list. A tool that needs
 * more belongs in its own module under {@code src/tools/extras}.
 */
class WeightRuleTest {

    private static final Path LIST = Path.of("target", "weight", "dependencies.txt");

    /** groupId:artifactId:scope, with "(optional)" where Maven says so. */
    private static final Set<String> ALLOWED =
            Set.of(
                    "io.github.srijithunni7182:ai-agent4j:compile",
                    "com.fasterxml.jackson.core:jackson-databind:compile",
                    "org.eclipse.angus:angus-mail:compile (optional)");

    @Test
    void theModuleDependsOnlyOnTheCoreJacksonAndTheOptionalMailLibrary() throws IOException {
        assertThat(LIST)
                .as("the dependency list Maven writes before the tests (run them through Maven)")
                .isRegularFile();
        assertThat(shipped(Files.readAllLines(LIST))).containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    /**
     * The entries an application inherits: every direct dependency except test-scoped ones, as
     * groupId:artifactId:scope. Maven's lines read {@code group:artifact:jar:version:scope}, with
     * an optional marker and module note after them.
     */
    static Set<String> shipped(Iterable<String> lines) {
        Set<String> out = new TreeSet<>();
        for (String raw : lines) {
            // Maven colours the module note when the build runs in a terminal
            String line = raw.replaceAll("\u001B\\[[0-9;]*m", "").strip();
            int note = line.indexOf(" -- ");
            if (note >= 0) line = line.substring(0, note).strip();
            boolean optional = line.endsWith("(optional)");
            if (optional) line = line.substring(0, line.length() - "(optional)".length()).strip();
            String[] parts = line.split(":");
            if (parts.length < 5) continue;
            String scope = parts[parts.length - 1];
            if (scope.equals("test")) continue;
            out.add(parts[0] + ":" + parts[1] + ":" + scope + (optional ? " (optional)" : ""));
        }
        return out;
    }

    @Test
    void readsMavensLinesAndLeavesOutTestScope() {
        assertThat(
                        shipped(
                                java.util.List.of(
                                        "The following files have been resolved:",
                                        "   a.b:core:jar:1.0:compile\u001B[36m -- module core\u001B[m",
                                        "   a.b:mail:jar:2.0:compile (optional) -- module mail",
                                        "   a.b:junit:jar:5:test",
                                        "   a.b:extra:jar:1:runtime")))
                .containsExactly(
                        "a.b:core:compile", "a.b:extra:runtime", "a.b:mail:compile (optional)");
    }
}
