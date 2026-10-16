package io.github.llm4j.nirmaan.tasks;

import static io.github.llm4j.nirmaan.tasks.TaskTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTasksTest {

    @TempDir Path workspace;
    Path code;

    @BeforeEach
    void setUp() {
        TaskTestSupport.newWorkspace(workspace);
        code = Sandbox.code("p1");
    }

    private static Map<String, Object> file(String path, String content) {
        return Map.of("path", path, "content", content);
    }

    @Test
    void savesFilesIncludingNestedFoldersAndRemovesAMarkdownFence() throws Exception {
        var result = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(
                file("src/app.py", "print('hi')\n"),
                file("tests/test_app.py", "```python\ndef test_x():\n    assert True\n```"))));

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(Files.readString(code.resolve("src/app.py"))).isEqualTo("print('hi')\n");
        assertThat(Files.readString(code.resolve("tests/test_app.py"))).isEqualTo("def test_x():\n    assert True\n");
    }

    @Test
    void refusesPathsThatEscapeTheProjectAndWritesNothingFromThatBatch() throws Exception {
        for (String bad : List.of("../evil.txt", "/etc/passwd", "a/../../evil.txt", "target/classes/A.class", "..\\evil.txt")) {
            var result = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("good.txt", "x"), file(bad, "y"))));

            assertThat(result.outcome()).as(bad).isEqualTo("rejected");
        }
        assertThat(code.resolve("good.txt")).doesNotExist();
        assertThat(workspace.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void refusesAnEmptyBatchAndAnOversizedFile() throws Exception {
        assertThat(run(new SaveFiles(), Map.of("project", "p1", "files", List.of())).outcome()).isEqualTo("rejected");
        String huge = "x".repeat(SaveFiles.MAX_BYTES + 1);
        assertThat(run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("big.txt", huge)))).outcome()).isEqualTo("rejected");
    }

    @Test
    void aProjectIdCannotNameAnotherFolder() {
        org.junit.jupiter.api.Assertions.assertThrows(io.github.llm4j.agent.task.TaskNotPerformed.class,
                () -> run(new SaveFiles(), Map.of("project", "../x", "files", List.of(file("a.txt", "a")))));
    }

    @Test
    void savesADocument() throws Exception {
        var result = run(new SaveDocument(), Map.of("project", "p1", "path", "PRD.md", "content", "# PRD"));

        assertThat(result.value()).isEqualTo("PRD.md");
        assertThat(Files.readString(code.resolve("PRD.md"))).isEqualTo("# PRD");
        assertThat(run(new SaveDocument(), Map.of("project", "p1", "path", "../x.md", "content", "x")).toString()).contains("leaves the project");
    }

    @Test
    void readCodeShowsEverythingOrOnlyWhatALogNames() throws Exception {
        TaskTestSupport.write(code, "src/Calc.java", "class Calc {}");
        TaskTestSupport.write(code, "src/Other.java", "class Other {}");
        TaskTestSupport.write(code, "src/test/CalcTest.java", "class CalcTest {}");
        TaskTestSupport.write(code, "pom.xml", "<project/>");
        TaskTestSupport.write(code, "target/classes/Junk.java", "class Junk {}");

        String all = String.valueOf(run(new ReadCode(), Map.of("project", "p1", "focus", "")).value());
        String focused = String.valueOf(run(new ReadCode(), Map.of("project", "p1", "focus", "Calc.java:12: error: cannot find symbol")).value());

        assertThat(all).contains("Calc.java", "Other.java", "CalcTest.java", "pom.xml").doesNotContain("Junk");
        assertThat(focused).contains("Calc.java", "CalcTest.java", "pom.xml").doesNotContain("class Other").contains("not shown");
    }

    @Test
    void checksThatUnitTestsExist() throws Exception {
        assertThat(run(new CheckTestsExist(), Map.of("project", "p1")).outcome()).isEqualTo("no_tests");
        TaskTestSupport.write(code, "src/main.py", "x = 1");
        TaskTestSupport.write(code, "e2e-tests/run_e2e.py", "assert True");
        assertThat(run(new CheckTestsExist(), Map.of("project", "p1")).outcome()).as("an end to end suite is not a unit test").isEqualTo("no_tests");
        TaskTestSupport.write(code, "tests/test_main.py", "def test_x():\n    assert 1\n");
        assertThat(run(new CheckTestsExist(), Map.of("project", "p1")).outcome()).isEqualTo("has_tests");
    }

    @Test
    void aFreshStartKeepsTestsBuildFilesAndDocumentsOnly() throws Exception {
        TaskTestSupport.write(code, "src/main/java/App.java", "class App {}");
        TaskTestSupport.write(code, "src/test/java/AppTest.java", "class AppTest {}");
        TaskTestSupport.write(code, "pom.xml", "<project/>");
        TaskTestSupport.write(code, "README.md", "# readme");
        TaskTestSupport.write(code, "e2e-tests/run.sh", "echo");

        var result = run(new ClearImplementation(), Map.of("project", "p1"));

        assertThat(result.toMap()).containsEntry("removed", 1);
        assertThat(code.resolve("src/main/java/App.java")).doesNotExist();
        assertThat(code.resolve("src/test/java/AppTest.java")).exists();
        assertThat(code.resolve("pom.xml")).exists();
        assertThat(code.resolve("README.md")).exists();
        assertThat(code.resolve("e2e-tests/run.sh")).exists();
    }

    @Test
    void packagesTheProjectWithoutBuildOutput() throws Exception {
        TaskTestSupport.write(code, "src/app.py", "x");
        TaskTestSupport.write(code, "node_modules/lib/index.js", "y");

        var result = run(new PackageProject(), Map.of("project", "p1"));

        assertThat(result.value()).isEqualTo("p1_FINAL.zip");
        try (ZipFile zip = new ZipFile(code.getParent().resolve("p1_FINAL.zip").toFile())) {
            assertThat(zip.stream().map(e -> e.getName())).containsExactly("src/app.py");
        }
    }

    @Test
    void existingTestsAreLockedByDefaultAndNewOnesMayBeAdded() throws Exception {
        TaskTestSupport.write(code, "test_calc.py", "assert add(1, 2) == 3\n");

        var tampered = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("calc.py", "def add(a, b): return 3"), file("test_calc.py", "assert True\n"))));
        var added = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("test_more.py", "assert add(2, 2) == 4\n"))));

        assertThat(tampered.outcome()).isEqualTo("rejected");
        assertThat(tampered.reason()).contains("test_calc.py", "locked", "Fix the code");
        assertThat(code.resolve("calc.py")).as("a batch with a locked test is refused whole").doesNotExist();
        assertThat(Files.readString(code.resolve("test_calc.py"))).isEqualTo("assert add(1, 2) == 3\n");
        assertThat(added.outcome()).isEqualTo("ok");
    }

    @Test
    void writingTheSameTestAgainIsNotAChange() throws Exception {
        TaskTestSupport.write(code, "test_calc.py", "assert add(1, 2) == 3\n");

        var result = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("test_calc.py", "assert add(1, 2) == 3"))));

        assertThat(result.outcome()).isEqualTo("ok");
    }

    @Test
    void theEndToEndSuiteBelongsToTheQaEngineer() throws Exception {
        var byTheDeveloper = run(new SaveFiles(), Map.of("project", "p1", "files", List.of(file("e2e-tests/run.sh", "exit 0\n"))));
        var byTheQaEngineer = run(new SaveFiles(), Map.of("project", "p1", "may_change", "e2e", "files", List.of(file("e2e-tests/run.sh", "exit 1\n"))));
        TaskTestSupport.write(code, "test_calc.py", "assert 1\n");
        var qaTouchingUnitTests = run(new SaveFiles(), Map.of("project", "p1", "may_change", "e2e", "files", List.of(file("test_calc.py", "assert 2\n"))));

        assertThat(byTheDeveloper.outcome()).isEqualTo("rejected");
        assertThat(byTheDeveloper.reason()).contains("end to end");
        assertThat(byTheQaEngineer.outcome()).isEqualTo("ok");
        assertThat(qaTouchingUnitTests.outcome()).as("Drishti cannot loosen the unit tests").isEqualTo("rejected");
        assertThat(run(new SaveFiles(), Map.of("project", "p1", "may_change", "everything", "files", List.of(file("a.txt", "a")))).outcome()).isEqualTo("rejected");
    }
}
