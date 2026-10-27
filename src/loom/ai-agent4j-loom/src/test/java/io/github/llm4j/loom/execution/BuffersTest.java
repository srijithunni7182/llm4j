package io.github.llm4j.loom.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The text of unsaved editor buffers stands in for files, for one thread, and nothing else changes.
 */
class BuffersTest {

    @TempDir Path dir;

    @Test
    void withoutBuffersAFileIsReadFromDisk() throws Exception {
        Path f = dir.resolve("a.loom");
        Files.writeString(f, "disk");
        assertThat(Buffers.read(f)).isEqualTo("disk");
        assertThat(Buffers.exists(f)).isTrue();
        assertThat(Buffers.exists(dir.resolve("gone.loom"))).isFalse();
    }

    @Test
    void aBufferReplacesTheFileAndEveryReadIsNoted() throws Exception {
        Path f = dir.resolve("a.loom").toAbsolutePath().normalize();
        Path g = dir.resolve("b.loom").toAbsolutePath().normalize();
        Files.writeString(f, "disk");
        Files.writeString(g, "other");
        Set<Path> reads = new LinkedHashSet<>();
        String seen =
                Buffers.with(
                        Map.of(f, "buffer"), reads, () -> Buffers.read(f) + "+" + Buffers.read(g));
        assertThat(seen).isEqualTo("buffer+other");
        assertThat(reads).containsExactly(f, g);
        assertThat(Files.readString(f)).as("the file is untouched").isEqualTo("disk");
    }

    @Test
    void aBufferForAFileThatIsNotOnDiskStillExists() throws Exception {
        Path f = dir.resolve("new.loom").toAbsolutePath().normalize();
        assertThat(Buffers.with(Map.of(f, "x"), null, () -> Buffers.exists(f))).isTrue();
        assertThat(Buffers.exists(f)).as("outside the scope it is gone").isFalse();
    }

    @Test
    void theScopeEndsWithTheCallAndDoesNotLeakToOtherThreads() throws Exception {
        Path f = dir.resolve("a.loom").toAbsolutePath().normalize();
        Files.writeString(f, "disk");
        String[] other = new String[1];
        Buffers.with(
                Map.of(f, "buffer"),
                null,
                () -> {
                    Thread t =
                            new Thread(
                                    () -> {
                                        try {
                                            other[0] = Buffers.read(f);
                                        } catch (Exception e) {
                                            other[0] = e.toString();
                                        }
                                    });
                    t.start();
                    t.join();
                    return null;
                });
        assertThat(other[0]).isEqualTo("disk");
        assertThat(Buffers.read(f)).isEqualTo("disk");
    }

    @Test
    void theLoaderReadsAnImportThroughTheBuffer() throws Exception {
        Path lib = dir.resolve("lib.loom").toAbsolutePath().normalize();
        Path main = dir.resolve("main.loom").toAbsolutePath().normalize();
        Files.writeString(lib, "agent A { model: \"gemini-2.5-flash\"  system: \"disk\" }\n");
        Files.writeString(
                main,
                "import \"lib.loom\"\nworkflow Main(x) { delegate \"hi\" to A -> r  return r }\n");
        var loaded =
                Buffers.with(
                        Map.of(
                                lib,
                                "agent A { model: \"gemini-2.5-flash\"  system: \"from the buffer\" }\n"),
                        null,
                        () -> new LoomLoader().load(main.toString()));
        assertThat(loaded.getAgents().get(0).getSystemPrompt()).contains("from the buffer");
    }
}
