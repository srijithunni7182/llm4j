package io.github.llm4j.loom.generate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R20 of spec 12: a change is a patch of named edits, so the diff is reviewable and other lines are
 * untouched.
 */
class PatchSetTest {

    static String edit(String path, String search, String replace) {
        return "=== edit: "
                + path
                + " ===\n<<<<<<< SEARCH\n"
                + search
                + "\n=======\n"
                + replace
                + "\n>>>>>>> REPLACE\n=== end ===\n";
    }

    private final Map<String, String> base =
            new LinkedHashMap<>(Map.of("main.loom", "a\nb\nc\n", "README.md", "# hi\n"));

    @Test
    void anEditReplacesExactlyTheTextItNamesAndNothingElse() {
        PatchSet p = PatchSet.parse("Sure.\n" + edit("main.loom", "b", "B"));
        PatchSet.Applied a = p.apply(base);
        assertThat(a.problems()).isEmpty();
        assertThat(a.changed()).containsOnlyKeys("main.loom");
        assertThat(a.changed().get("main.loom")).isEqualTo("a\nB\nc\n");
    }

    @Test
    void severalPairsInOneBlockAndSeveralFilesAreApplied() {
        String reply =
                "=== edit: main.loom ===\n<<<<<<< SEARCH\na\n=======\nA\n>>>>>>> REPLACE\n<<<<<<< SEARCH\nc\n=======\nC\n>>>>>>> REPLACE\n=== end ===\n"
                        + edit("README.md", "# hi", "# hello");
        PatchSet.Applied a = PatchSet.parse(reply).apply(base);
        assertThat(a.problems()).isEmpty();
        assertThat(a.changed().get("main.loom")).isEqualTo("A\nb\nC\n");
        assertThat(a.changed().get("README.md")).isEqualTo("# hello\n");
    }

    @Test
    void textThatIsNotThereOrIsThereTwiceIsAProblemTheModelCanFix() {
        PatchSet.Applied missing = PatchSet.parse(edit("main.loom", "zzz", "y")).apply(base);
        assertThat(missing.problems())
                .anyMatch(
                        s ->
                                s.contains(
                                        "In main.loom the text to replace is not there, word for word"));
        Map<String, String> twice = Map.of("main.loom", "x\nx\n");
        assertThat(PatchSet.parse(edit("main.loom", "x", "y")).apply(twice).problems())
                .anyMatch(s -> s.contains("occurs more than once"));
        assertThat(PatchSet.parse(edit("main.loom", " ", "y")).apply(base).problems())
                .anyMatch(s -> s.contains("nothing to search for"));
    }

    @Test
    void anEditOfAFileThatDoesNotExistAndANewFileThatDoesAreTold() {
        assertThat(PatchSet.parse(edit("prompts/new.md", "a", "b")).apply(base).problems())
                .anyMatch(s -> s.contains("does not have") && s.contains("file block"));
        PatchSet.Applied exists =
                PatchSet.parse("=== file: README.md ===\nx\n=== end ===").apply(base);
        assertThat(exists.problems()).anyMatch(s -> s.contains("already exists"));
        PatchSet.Applied added =
                PatchSet.parse("=== file: prompts/new.md ===\nHello\n=== end ===").apply(base);
        assertThat(added.problems()).isEmpty();
        assertThat(added.changed()).containsEntry("prompts/new.md", "Hello\n");
    }

    @Test
    void pathsOutsideTheProjectAndKeysAreRefused() {
        for (String bad :
                new String[] {
                    "../x.loom", ".env", "/etc/passwd", "src/Main.java", "prompts/../../x.md"
                }) {
            PatchSet.Applied a =
                    PatchSet.parse("=== file: " + bad + " ===\nx\n=== end ===").apply(base);
            assertThat(a.problems()).as(bad).isNotEmpty();
            assertThat(a.changed()).as(bad).doesNotContainKey(bad);
        }
        assertThat(
                        PatchSet.parse(
                                        edit(
                                                "main.loom",
                                                "b",
                                                "key sk-abcdefghijklmnopqrstuvwxyz0123456789"))
                                .apply(base)
                                .problems())
                .anyMatch(s -> s.contains("shaped like a key"));
    }

    @Test
    void aQuestionIsAQuestionAndNotAPatch() {
        PatchSet p =
                PatchSet.parse(
                        "=== question ===\nWho approves: the lead or the owner?\n=== end ===");
        assertThat(p.question()).isEqualTo("Who approves: the lead or the owner?");
        assertThat(p.isEmpty()).isTrue();
        assertThat(PatchSet.parse("no blocks").isEmpty()).isTrue();
        assertThat(PatchSet.parse(null).isEmpty()).isTrue();
    }

    @Test
    void theDiffNamesOnlyTheFilesThatChangedAndShowsTheLines() {
        Map<String, String> after = new LinkedHashMap<>(base);
        after.put("main.loom", "a\nB\nc\n");
        String diff = PatchSet.diff(base, after);
        assertThat(diff)
                .contains("main.loom")
                .contains("-b")
                .contains("+B")
                .doesNotContain("README");
    }

    @Test
    void aSavedPatchRoundTripsAndRemembersWhatItWasMadeAgainst() {
        Map<String, String> after = new LinkedHashMap<>(base);
        after.put("main.loom", "a\nB\nc\n");
        after.put("prompts/new.md", "Hello\n");
        PatchSet.Saved saved = PatchSet.read(PatchSet.save("make b big", base, after));
        assertThat(saved.request()).isEqualTo("make b big");
        assertThat(saved.files()).containsOnlyKeys("main.loom", "prompts/new.md");
        assertThat(saved.was())
                .containsOnlyKeys("main.loom")
                .containsEntry("main.loom", PatchSet.sha("a\nb\nc\n"));
    }
}
