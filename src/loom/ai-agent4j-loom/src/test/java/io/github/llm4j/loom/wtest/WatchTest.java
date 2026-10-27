package io.github.llm4j.loom.wtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R31 of spec 11: what is watched, what counts as a change, and what a change reruns. */
class WatchTest {

    @TempDir Path dir;

    TestRunner.Suite suite() throws Exception {
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/helper.md"), "Help.");
        return Wtest.load(
                dir,
                Wtest.TRIAGE,
                Wtest.triageCases(
                        "  - name: c\n    input: { message: hi }\n    models:\n      Intent: { kind: refund, urgent: 'no' }\n      Escalator: ok\n"));
    }

    @Test
    void theScriptThePromptsAndTheTestFilesAreWatched() throws Exception {
        TestRunner.Suite s = suite();
        List<Path> files =
                Watch.watched(dir.resolve("main.loom"), List.of(dir.resolve("tests")), s);
        assertThat(files)
                .extracting(p -> p.getFileName().toString())
                .contains("main.loom", "helper.md", "main.test.yaml");
    }

    @Test
    void aChangeIsANewTimeANewFileOrAGoneFile() throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        Map<Path, Long> before = Watch.snapshot(List.of(a, b));
        assertThat(Watch.changed(before, Watch.snapshot(List.of(a, b)))).isEmpty();
        Files.setLastModifiedTime(a, FileTime.fromMillis(before.get(a) + 5000));
        assertThat(Watch.changed(before, Watch.snapshot(List.of(a, b)))).containsExactly(a);
        Files.delete(b);
        assertThat(Watch.changed(before, Watch.snapshot(List.of(a, b)))).contains(b);
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        assertThat(Watch.changed(before, Watch.snapshot(List.of(a, c)))).contains(c, b);
    }

    @Test
    void onlyAChangedTestFileRerunsOnlyItsCases() throws Exception {
        TestRunner.Suite s = suite();
        Path test = dir.resolve("tests/main.test.yaml").toAbsolutePath().normalize();
        Path script = dir.resolve("main.loom").toAbsolutePath().normalize();
        assertThat(Watch.onlyTestFiles(Set.of(test), s.files())).containsExactly(test);
        assertThat(Watch.onlyTestFiles(Set.of(script), s.files())).isEmpty();
        assertThat(Watch.onlyTestFiles(Set.of(test, script), s.files()))
                .as("the script too: all cases")
                .isEmpty();
        assertThat(Watch.onlyTestFiles(Set.of(dir.resolve("prompts/helper.md")), s.files()))
                .isEmpty();
    }
}
