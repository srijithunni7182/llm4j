package io.github.llm4j.loom.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** V2.1, V2.5, V2.6: the files an entry script reaches, and what is reported when some cannot be loaded. */
class ImportClosureLoaderTest {

    private final ImportClosureLoader loader = new ImportClosureLoader();

    private static Path path(String relative) {
        return Path.of("src/test/resources").resolve(relative).toAbsolutePath().normalize();
    }

    private static List<String> names(java.util.Collection<Path> paths) {
        return paths.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
    }

    @Test
    void aSimpleImportIsResolvedRelativeToTheImportingFile() {
        ImportClosure closure = loader.load(path("imports/parent.loom"));

        assertThat(names(closure.discovery())).containsExactly("parent.loom", "child.loom");
        assertThat(names(closure.imports().get(path("imports/parent.loom")))).containsExactly("child.loom");
        assertThat(closure.imports().get(path("imports/child.loom"))).isEmpty();
        assertThat(closure.diagnostics()).isEmpty();
    }

    @Test
    void importsAreMergedBeforeTheFileThatImportsThem() {
        ImportClosure closure = loader.load(path("imports/parent.loom"));

        assertThat(names(closure.scripts().keySet())).containsExactly("child.loom", "parent.loom");
    }

    @Test
    void aFileImportedByTwoFilesIsLoadedOnceAndListedByBoth() {
        ImportClosure closure = loader.load(path("graph/diamond/a.loom"));

        assertThat(names(closure.discovery())).containsExactly("a.loom", "b.loom", "d.loom", "c.loom");
        assertThat(names(closure.imports().get(path("graph/diamond/b.loom")))).containsExactly("d.loom");
        assertThat(names(closure.imports().get(path("graph/diamond/c.loom")))).containsExactly("d.loom");
        assertThat(names(closure.scripts().keySet())).containsExactly("d.loom", "b.loom", "c.loom", "a.loom");
        assertThat(closure.diagnostics()).isEmpty();
    }

    @Test
    void anImportCycleEndsWithOneWarningNamingTheFilesAndBothGraphsStillLoad() {
        ImportClosure closure = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2), () -> loader.load(path("imports/circular_a.loom")));

        assertThat(names(closure.scripts().keySet())).containsExactlyInAnyOrder("circular_a.loom", "circular_b.loom");
        assertThat(closure.diagnostics()).hasSize(1);
        Diagnostic cycle = closure.diagnostics().get(0);
        assertThat(cycle.severity()).isEqualTo(Diagnostic.Severity.WARNING);
        assertThat(cycle.message()).contains("circular_a.loom → circular_b.loom → circular_a.loom");
        assertThat(cycle.file()).endsWith("circular_b.loom");
        assertThat(cycle.line()).isEqualTo(1);
    }

    @Test
    void aMissingImportIsAnErrorWithTheLineOfTheImportAndTheEntryStillLoads() {
        ImportClosure closure = loader.load(path("graph/missing_import.loom"));

        assertThat(closure.hasEntry()).isTrue();
        assertThat(closure.diagnostics()).hasSize(1);
        Diagnostic missing = closure.diagnostics().get(0);
        assertThat(missing.severity()).isEqualTo(Diagnostic.Severity.ERROR);
        assertThat(missing.file()).endsWith("missing_import.loom");
        assertThat(missing.line()).isEqualTo(1);
        assertThat(missing.message()).contains("not found").contains("nowhere.loom");
    }

    @Test
    void aSyntaxErrorInAnImportIsReportedWithItsFileAndLineAndTheEntryStillLoads() {
        ImportClosure closure = loader.load(path("graph/syntax_error_import/main.loom"));

        assertThat(names(closure.scripts().keySet())).containsExactly("main.loom");
        assertThat(closure.diagnostics()).hasSize(1);
        Diagnostic broken = closure.diagnostics().get(0);
        assertThat(broken.severity()).isEqualTo(Diagnostic.Severity.ERROR);
        assertThat(broken.file()).endsWith("broken.loom");
        assertThat(broken.line()).isEqualTo(5);
        assertThat(closure.imports().get(path("graph/syntax_error_import/main.loom"))).isEmpty();
    }

    @Test
    void aMissingEntryFileLeavesNoEntry() {
        ImportClosure closure = loader.load(path("graph/does_not_exist.loom"));

        assertThat(closure.hasEntry()).isFalse();
        assertThat(closure.diagnostics()).hasSize(1);
        assertThat(closure.diagnostics().get(0).severity()).isEqualTo(Diagnostic.Severity.ERROR);
    }

    @Test
    void aSyntaxErrorInTheEntryFileLeavesNoEntry(@TempDir Path dir) throws Exception {
        Path entry = Files.writeString(dir.resolve("bad.loom"), "workflow W() {\n  delegate \"x\" to A }\n");

        ImportClosure closure = loader.load(entry);

        assertThat(closure.hasEntry()).isFalse();
        assertThat(closure.diagnostics().get(0).line()).isEqualTo(2);
    }

    @Test
    void pathsWithSpacesWork(@TempDir Path dir) throws Exception {
        assertThat(loadFromFolder(dir, "my scripts")).isEmpty();
    }

    @Test
    void pathsWithUnicodeWorkWhereTheFileSystemCanNameThem(@TempDir Path dir) throws Exception {
        String encoding = System.getProperty("sun.jnu.encoding", "ASCII");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.charset.Charset.forName(encoding).newEncoder().canEncode('ü'),
                "the file system encoding cannot name ü");
        assertThat(loadFromFolder(dir, "scripts ü")).isEmpty();
    }

    private List<Diagnostic> loadFromFolder(Path dir, String folderName) throws Exception {
        Path folder = Files.createDirectories(dir.resolve(folderName));
        Files.writeString(folder.resolve("child file.loom"), "workflow Child() { note \"c\" }\n");
        Path entry = Files.writeString(
                folder.resolve("main.loom"), "import \"child file.loom\"\nworkflow M() { call Child() -> r }\n");

        ImportClosure closure = loader.load(entry);

        assertThat(closure.scripts()).hasSize(2);
        return closure.diagnostics();
    }
}
