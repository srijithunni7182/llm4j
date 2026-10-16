package io.github.llm4j.nirmaan.tasks;

import static io.github.llm4j.nirmaan.tasks.TaskTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecTasksTest {

    @TempDir Path workspace;

    @BeforeEach
    void setUp() {
        TaskTestSupport.newWorkspace(workspace);
    }

    private static Map<String, Object> spec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("stack", "Python 3 with pytest");
        spec.put("rationale", "small and testable");
        spec.put("requirements", List.of("prints the count", "exits 1 for a missing file"));
        spec.put("unit_test_strategy", "pytest");
        spec.put("e2e_strategy", "a shell script");
        spec.put("build_command", "pip install -r requirements.txt");
        spec.put("test_command", "pytest");
        spec.put("run_command", "python3 wc.py");
        spec.put("e2e_command", "bash e2e-tests/run.sh");
        spec.put("structure", List.of("wc.py", "test_wc.py"));
        spec.put("plan", List.of("write the test", "write the code"));
        spec.put("change_summary", "initial");
        return spec;
    }

    @Test
    void savedSpecComesBackAsFieldsAndAsSpecMd() throws Exception {
        var saved = run(new SaveSpec(), Map.of("project", "p1", "spec", spec()));
        var loaded = run(new LoadSpec(), Map.of("project", "p1"));

        assertThat(saved.outcome()).isEqualTo("ok");
        assertThat(loaded.toMap()).containsEntry("test_command", "pytest").containsEntry("e2e_command", "bash e2e-tests/run.sh").containsEntry("stack", "Python 3 with pytest");
        assertThat(String.valueOf(loaded.toMap().get("markdown"))).contains("Test Command: pytest", "- exits 1 for a missing file", "1. write the test");
        assertThat(Files.readString(Sandbox.code("p1").resolve("SPEC.md"))).contains("Build Command: pip install -r requirements.txt");
    }

    @Test
    void aSpecWithoutATestCommandIsRefusedSoRishiIsAskedAgain() throws Exception {
        Map<String, Object> bad = spec();
        bad.put("test_command", "None");

        var result = run(new SaveSpec(), Map.of("project", "p1", "spec", bad));

        assertThat(result.outcome()).isEqualTo("rejected");
        assertThat(result.reason()).contains("test_command");
        assertThat(Sandbox.code("p1").resolve("SPEC.md")).doesNotExist();
    }

    @Test
    void anUnsavedSpecLoadsAsMissingWithEmptyFieldsSoAScriptCanStillReadThem() throws Exception {
        var loaded = run(new LoadSpec(), Map.of("project", "never-saved"));

        assertThat(loaded.outcome()).isEqualTo("missing");
        assertThat(loaded.toMap()).containsKeys("markdown", "test_command", "build_command", "run_command", "e2e_command");
    }
}
