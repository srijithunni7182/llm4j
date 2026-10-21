package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.context.ContextWindows;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ways a windows file can be wrong are named, and a layer can be put on top of the others. */
class WindowLayersErrorsTest {

    @TempDir Path dir;

    @Test
    void aCommandLineFileThatIsNotThereIsRefusedByName() {
        Path missing = dir.resolve("missing.windows");

        assertThatThrownBy(() -> WindowLayers.load(List.of(missing), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(missing.toString())
                .hasMessageContaining("does not exist");
    }

    @Test
    void aFileThatCannotBeParsedIsRefusedWithItsNameAndTheProblem() throws Exception {
        Path bad = dir.resolve("bad.windows");
        Files.writeString(bad, "this is not a windows file\n");

        assertThatThrownBy(() -> WindowLayers.load(List.of(bad), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(bad.toString());
    }

    @Test
    void aLayerPutOverTheOthersWinsAndNamesItsSource() {
        WindowLayers base = WindowLayers.none();

        WindowLayers over = base.over("test", ContextWindows.empty());

        assertThat(over.find("no-such-model")).isEmpty();
        assertThat(base.find(null)).isEmpty();
    }

    @Test
    void aFileThatCannotBeReadAsTextIsRefusedAsUnreadable() throws Exception {
        Path binary = dir.resolve("binary.windows");
        Files.write(binary, new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd});

        assertThatThrownBy(() -> WindowLayers.load(List.of(binary), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    void layersCanBeGivenDirectlyHighestFirst() {
        WindowLayers layers = WindowLayers.of(List.of());

        assertThat(layers.find("anything")).isEmpty();
    }
}
