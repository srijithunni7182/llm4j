package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.state.RunRegistry;
import io.github.llm4j.loom.inspect.state.WindowBook;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R4: the context-window catalog is a configuration section like the prices: saved checked and
 * whole, kept, loaded again, applied at once.
 */
class WindowsConfigTest {

    @TempDir Path dir;

    private final RunRegistry registry = new RunRegistry();

    private FabricConfig config(RunRegistry r) {
        return new FabricConfig(dir, r, new PrintStream(new ByteArrayOutputStream()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> windows(FabricConfig c) {
        return (Map<String, Object>) (Object) c.view().get("windows");
    }

    @Test
    void theSectionIsThereWithNothingInIt() {
        Map<String, Object> w = windows(config(registry));
        assertThat(w).containsKeys("text", "summary", "warnings", "structured");
        assertThat((List<?>) w.get("structured")).isEmpty();
        assertThat(w.get("summary").toString()).contains("no windows");
    }

    @Test
    void aTextSaveIsCheckedKeptAndAppliedToEveryRun() {
        registry.setModels("p", "a".repeat(32), Map.of("Editor", "big-model"));
        FabricConfig c = config(registry);
        var result = c.save("windows", "big-model = 50000\nsmall = 8000\n", "admin");
        assertThat(result.saved()).as(result.toString()).isTrue();
        assertThat(registry.find("p", "a".repeat(32)).orElseThrow().windows().usable("big-model"))
                .isEqualTo(50_000L);
        assertThat(windows(c).get("summary").toString()).contains("2 model");
        assertThat(Files.isRegularFile(dir.resolve("config").resolve("windows.properties")))
                .isTrue();
    }

    @Test
    void aBadFileIsRefusedWithItsLinesAndNothingChanges() {
        FabricConfig c = config(registry);
        assertThat(c.save("windows", "good = 1000\n", "admin").saved()).isTrue();
        var bad = c.save("windows", "good = 2000\nbroken = lots\n", "admin");
        assertThat(bad.saved()).isFalse();
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.problems()).anySatisfy(p -> assertThat(p).contains("line 2"));
        assertThat((String) windows(c).get("text")).contains("good = 1000");
    }

    @Test
    void theStructuredFormGoesBothWays() {
        FabricConfig c = config(registry);
        var result =
                c.saveStructured(
                        "windows",
                        "[{\"model\":\"big-model\",\"window\":50000},{\"model\":\"small\",\"window\":8000}]",
                        "admin");
        assertThat(result.saved()).as(result.toString()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) windows(c).get("structured");
        assertThat(rows).extracting(r -> r.get("model")).containsExactly("big-model", "small");
        assertThat(rows.get(0)).containsEntry("window", 50_000L);
        assertThat((String) windows(c).get("text")).contains("big-model = 50000");
    }

    @Test
    void theStructuredFormRefusesWhatTheFileRefuses() {
        FabricConfig c = config(registry);
        assertThat(
                        c.saveStructured("windows", "[{\"model\":\"\",\"window\":50000}]", "admin")
                                .saved())
                .isFalse();
        assertThat(c.saveStructured("windows", "[{\"model\":\"m\",\"window\":0}]", "admin").saved())
                .isFalse();
        assertThat(
                        c.saveStructured(
                                        "windows",
                                        "[{\"model\":\"m\",\"window\":\"lots\"}]",
                                        "admin")
                                .saved())
                .isFalse();
        assertThat(c.saveStructured("windows", "{\"not\":\"a list\"}", "admin").saved()).isFalse();
        assertThat(
                        c.saveStructured(
                                        "windows",
                                        "[{\"model\":\"m\",\"window\":10},{\"model\":\"m\",\"window\":20}]",
                                        "admin")
                                .saved())
                .isFalse();
    }

    @Test
    void whatWasKeptIsLoadedAgainAtStartAndAnOldStoreWithoutTheFileStillLoads() {
        config(registry).save("windows", "kept-model = 12345\n", "admin");
        RunRegistry again = new RunRegistry();
        FabricConfig c = config(again);
        c.load();
        again.setModels("p", "b".repeat(32), Map.of());
        assertThat(again.find("p", "b".repeat(32)).orElseThrow().windows().usable("kept-model"))
                .isEqualTo(12_345L);

        Path other = dir.resolve("old");
        FabricConfig old =
                new FabricConfig(
                        other, new RunRegistry(), new PrintStream(new ByteArrayOutputStream()));
        old.load();
        assertThat((List<?>) windows(old).get("structured")).isEmpty();
    }

    @Test
    void aFileThatNoLongerReadsIsReportedAndNotUsed() throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config").resolve("windows.properties"), "garbage\n");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        FabricConfig c = new FabricConfig(dir, registry, new PrintStream(log));
        c.load();
        assertThat(log.toString()).contains("windows.properties");
        assertThat((List<?>) windows(c).get("structured")).isEmpty();
    }

    @Test
    void theOtherSectionsAreUntouched() {
        FabricConfig c = config(registry);
        assertThat(c.view()).containsKeys("rules", "prices", "windows");
        assertThat(c.save("prices", "m = 1 / 2\n", "admin").saved()).isTrue();
        assertThat(WindowBook.parse((String) windows(c).get("text")).book().isEmpty()).isTrue();
    }
}
