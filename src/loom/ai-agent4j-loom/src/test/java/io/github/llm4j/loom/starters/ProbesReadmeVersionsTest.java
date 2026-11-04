package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase 29: the versions named at the top of probes/README.md are the versions the probes pin, so a
 * statement about another framework always names what it was run on.
 */
class ProbesReadmeVersionsTest {

    private static final Path PROBES = Path.of("../../../probes").toAbsolutePath().normalize();
    private static final Pattern PIN = Pattern.compile("^([A-Za-z0-9_.-]+)==([0-9][A-Za-z0-9_.-]*)\\s*$", Pattern.MULTILINE);
    private static final List<String> NEW_PROBES =
            List.of(
                    "extractor-isolation",
                    "large-schema-with-tools",
                    "exit-check",
                    "approval-placement",
                    "untested-guard",
                    "tool-effects",
                    "handoff-check",
                    "long-loop-progress",
                    "route-allowlist");

    private static String versionsLine() throws Exception {
        return Files.readAllLines(PROBES.resolve("README.md")).stream()
                .filter(l -> l.startsWith("Versions:"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void everyPinnedVersionIsNamedAtTheTopOfTheReadme() throws Exception {
        String line = versionsLine().replace("-", "").toLowerCase();
        for (String file : List.of("requirements.txt")) {
            Matcher m = PIN.matcher(Files.readString(PROBES.resolve(file)));
            int pins = 0;
            while (m.find()) {
                pins++;
                String name = m.group(1).replace("-", "").toLowerCase();
                String version = m.group(2).toLowerCase();
                assertThat(line).as(m.group(1) + " " + version).contains(name).contains(version);
            }
            assertThat(pins).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void everyNewProbePinsTheSameVersionsAsTheSharedFile() throws Exception {
        String shared = Files.readString(PROBES.resolve("requirements.txt"));
        for (String probe : NEW_PROBES) {
            Path file = PROBES.resolve(probe).resolve("python/requirements.txt");
            assertThat(file).as(probe).isRegularFile();
            Matcher m = PIN.matcher(Files.readString(file));
            int pins = 0;
            while (m.find()) {
                pins++;
                assertThat(shared).as(probe + " pins " + m.group()).contains(m.group(1) + "==" + m.group(2));
            }
            assertThat(pins).as(probe).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void everyNewProbeHasBothSidesAndStoredResults() throws Exception {
        for (String probe : NEW_PROBES) {
            for (String f : List.of("python/probe.py", "python/run.sh", "loom/run.sh", "results/python.md", "results/loom.txt"))
                assertThat(PROBES.resolve(probe).resolve(f)).as(probe + "/" + f).isRegularFile();
            assertThat(Files.readString(PROBES.resolve(probe).resolve("results/python.md")))
                    .as(probe + " names its versions")
                    .startsWith("Versions: ");
        }
    }

    @Test
    void theStoredResultsCarryNoKeyOrLocalPath() throws Exception {
        try (Stream<Path> files = Files.walk(PROBES)) {
            for (Path f : files.filter(p -> p.toString().contains("/results/") && Files.isRegularFile(p)).toList()) {
                String text = Files.readString(f);
                assertThat(text).as(f.toString()).doesNotContain("/home/").doesNotContain("/tmp/").doesNotContain("test-key");
            }
        }
    }

    @Test
    void theReadmeHasASectionForEachNewProbe() throws Exception {
        String readme = Files.readString(PROBES.resolve("README.md"));
        for (int p = 9; p <= 17; p++) assertThat(readme).contains("### P" + p + ". ");
        for (String probe : NEW_PROBES) assertThat(readme).as(probe).contains("`" + probe + "/`");
    }

    @Test
    void theDeliberatelyBrokenProbeScriptsReallyAreRefusedByWeaveCheck() throws Exception {
        // RepositoryScriptsTest leaves these three out because they are meant to fail; their stored
        // output must show the refusal, so a script that quietly starts passing is noticed
        assertThat(Files.readString(PROBES.resolve("exit-check/results/loom.txt"))).contains("✗ line 10: return in Main");
        assertThat(Files.readString(PROBES.resolve("tool-effects/results/loom.txt"))).contains("✗ line 7: use Notify");
        assertThat(Files.readString(PROBES.resolve("route-allowlist/results/loom.txt"))).contains("a model chooses which agent runs here");
    }
}
