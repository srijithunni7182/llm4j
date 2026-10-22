package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R5: the windows are configured outside the script, in layers. */
class ContextWindowsLayeringTest {

    @TempDir Path dir;

    private Path file(String name, String text) throws Exception {
        Path f = dir.resolve(name);
        Files.writeString(f, text);
        return f;
    }

    @Test
    void theCatalogueIsTheBase() {
        WindowLayers layers = WindowLayers.load(List.of(), null, null);
        assertThat(layers.find("claude-sonnet-5-5"))
                .isPresent()
                .get()
                .extracting(WindowLayers.Found::source)
                .isEqualTo(WindowLayers.CATALOGUE);
        assertThat(layers.find("never-heard-of-it")).isEmpty();
    }

    @Test
    void aFileGivesAModelItsWindowAndNamesItself() throws Exception {
        Path f = file("mine.windows", "my-finetune = 32768 / 4096  # comment\n");
        var found = WindowLayers.load(List.of(f), null, null).find("my-finetune").orElseThrow();
        assertThat(found.window().tokens()).isEqualTo(32768);
        assertThat(found.window().reservedForOutput()).isEqualTo(4096);
        assertThat(found.source()).isEqualTo(f.toString());
    }

    @Test
    void precedenceIsCommandLineThenBesideThenUserThenCatalogue() throws Exception {
        Path cli1 = file("cli1", "m = 1000 / 100\n");
        Path cli2 = file("cli2", "m = 2000 / 100\nonly2 = 5000 / 100\n");
        Path beside = file("beside", "m = 3000 / 100\nb = 3000 / 100\nonly_b = 7000 / 100\n");
        Path user = file("user", "m = 4000 / 100\nb = 4000 / 100\nu = 4000 / 100\n");
        WindowLayers layers = WindowLayers.load(List.of(cli1, cli2), beside, user);
        assertThat(layers.find("m").orElseThrow().window().tokens())
                .as("the last command-line file wins")
                .isEqualTo(2000);
        assertThat(layers.find("only2").orElseThrow().window().tokens()).isEqualTo(5000);
        assertThat(layers.find("b").orElseThrow().window().tokens())
                .as("beside beats the user's file")
                .isEqualTo(3000);
        assertThat(layers.find("u").orElseThrow().window().tokens()).isEqualTo(4000);
        assertThat(layers.find("claude-sonnet-5-5")).isPresent();
    }

    @Test
    void aCatalogueModelCanBeOverridden() throws Exception {
        Path f = file("o", "claude-sonnet-5-5 = 50000 / 1000\n");
        assertThat(
                        WindowLayers.load(List.of(f), null, null)
                                .find("claude-sonnet-5-5")
                                .orElseThrow()
                                .window()
                                .tokens())
                .isEqualTo(50000);
    }

    @Test
    void absentBesideAndUserFilesAreSkipped() {
        assertThat(
                        WindowLayers.load(
                                        List.of(), dir.resolve("nope.windows"), dir.resolve("nope"))
                                .layers())
                .hasSize(1);
    }

    @Test
    void aMissingCommandLineFileIsAClearError() {
        assertThatThrownBy(() -> WindowLayers.load(List.of(dir.resolve("missing")), null, null))
                .hasMessageContaining("missing")
                .hasMessageContaining("does not exist");
    }

    @Test
    void aBadLineNamesTheFileAndTheLine() throws Exception {
        Path f = file("bad.windows", "ok = 1000 / 100\nbroken line\n");
        assertThatThrownBy(() -> WindowLayers.load(List.of(f), null, null))
                .hasMessageContaining("bad.windows")
                .hasMessageContaining("2");
    }

    @Test
    void overAddsALayerOnTop() throws Exception {
        WindowLayers base = WindowLayers.catalogue();
        var layered =
                base.over(
                        "test",
                        io.github.llm4j.context.ContextWindows.empty()
                                .with("x", io.github.llm4j.context.ContextWindow.of(9000)));
        assertThat(layered.find("x").orElseThrow().source()).isEqualTo("test");
        assertThat(base.find("x")).isEmpty();
    }

    @Test
    void fileNamesBesideAScript() {
        assertThat(ContextFiles.sibling(Path.of("/a/b/flow.loom"), "windows"))
                .isEqualTo(Path.of("/a/b/flow.windows"));
        assertThat(ContextFiles.sibling(Path.of("/a/b/flow"), "loct"))
                .isEqualTo(Path.of("/a/b/flow.loct"));
    }

    @Test
    void contextFilesFromTheCommandLineAreUsedAndBesideFilesAreFound() throws Exception {
        Path script = dir.resolve("flow.loom");
        Files.writeString(script, "x");
        Path beside = file("flow.windows", "b-model = 12000 / 1000\n");
        Path named = file("named.windows", "n-model = 15000 / 1000\n");
        Path loct = file("named.loct", "a = x.Y\n");
        Path besideLoct = file("flow.loct", "b = x.Z\n");
        try {
            ContextFiles.use(script, List.of(named.toFile()), List.of(loct.toFile()));
            WindowLayers layers = ContextFiles.current().windowLayers();
            assertThat(layers.find("b-model")).isPresent();
            assertThat(layers.find("n-model")).isPresent();
            assertThat(ContextFiles.current().loctFiles())
                    .containsExactly(
                            besideLoct.toAbsolutePath().normalize(),
                            loct.toAbsolutePath().normalize());
            assertThatThrownBy(
                            () ->
                                    ContextFiles.use(
                                            script, List.of(dir.resolve("gone").toFile()), null))
                    .hasMessageContaining("--context-windows");
        } finally {
            ContextFiles.clear();
        }
    }

    @Test
    void besideOnlyAppliesWhenNoScriptWasNamed() throws Exception {
        try {
            ContextFiles.clear();
            Path script = dir.resolve("a.loom");
            ContextFiles.beside(script);
            Path other = dir.resolve("other.loom");
            ContextFiles.beside(other);
            Files.writeString(dir.resolve("a.windows"), "z = 1000 / 100\n");
            Files.writeString(dir.resolve("other.windows"), "y = 1000 / 100\n");
            assertThat(ContextFiles.current().windowLayers().find("z")).isPresent();
            assertThat(ContextFiles.current().windowLayers().find("y")).isEmpty();
        } finally {
            ContextFiles.clear();
        }
    }
}
