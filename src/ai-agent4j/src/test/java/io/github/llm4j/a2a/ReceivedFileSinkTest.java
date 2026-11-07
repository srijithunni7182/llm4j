package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.model.TinyMedia;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceivedFileSinkTest {

    @TempDir Path root;

    private ReceivedFileSink sink(MediaPolicy policy, int maxItems) {
        return new ReceivedFileSink(root, "run-1", policy, maxItems);
    }

    private static void write(OutputStream out, byte[] bytes) throws IOException {
        try (out) {
            out.write(bytes);
        }
    }

    private long filesUnderRoot() throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aGoodFileLandsUnderReceivedRunWithAGeneratedNameAndItsHash() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        write(sink.open("../../etc/passwd", "image/png"), TinyMedia.png());
        var file = sink.files().get(0);
        assertThat(file.path()).startsWith(root.resolve("received").resolve("run-1"));
        assertThat(file.path().getFileName().toString()).doesNotContain("passwd");
        assertThat(file.label()).isEqualTo("../../etc/passwd");
        assertThat(file.kind()).isEqualTo(MediaKind.IMAGE);
        assertThat(file.mediaType()).isEqualTo("image/png");
        assertThat(file.size()).isEqualTo(TinyMedia.png().length);
        assertThat(file.sha256()).hasSize(64);
        assertThat(Files.readAllBytes(file.path())).isEqualTo(TinyMedia.png());
    }

    @Test
    void theTypeIsDecidedFromTheBytesNotTheClaim() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        write(sink.open("doc", ""), TinyMedia.pdf());
        assertThat(sink.files().get(0).mediaType()).isEqualTo("application/pdf");
        assertThat(sink.files().get(0).kind()).isEqualTo(MediaKind.DOCUMENT);
    }

    @Test
    void aKindThatIsNotWhatWasClaimedIsRefusedAndRemoved() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        var out = sink.open("pic", "image/png");
        assertThatThrownBy(() -> write(out, TinyMedia.pdf()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("claimed");
        assertThat(sink.files()).isEmpty();
        assertThat(filesUnderRoot()).isZero();
    }

    @Test
    void svgAndUnknownBytesAreRefusedAndRemoved() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        assertThatThrownBy(() -> write(sink.open("a", "image/svg+xml"), TinyMedia.svg()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> write(sink.open("b", ""), TinyMedia.unknown()))
                .isInstanceOf(IOException.class);
        assertThat(filesUnderRoot()).isZero();
        assertThat(sink.files()).isEmpty();
    }

    @Test
    void theWriteStopsAtTheSizeCapWhileItIsHappening() throws IOException {
        var policy = MediaPolicy.builder().maxBytesPerItem(100).build();
        var sink = sink(policy, 4);
        var out = sink.open("big", "");
        out.write(new byte[60]);
        assertThatThrownBy(() -> out.write(new byte[60]))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("100");
        assertThatThrownBy(() -> out.write(1)).isInstanceOf(IOException.class);
        assertThatThrownBy(out::close).isInstanceOf(IOException.class);
        assertThat(filesUnderRoot()).isZero();
    }

    @Test
    void aFailedWriteLeavesNothing() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        var out = sink.open("half", "");
        out.write(new byte[] {1, 2, 3});
        assertThatThrownBy(out::close).isInstanceOf(IOException.class);
        assertThat(filesUnderRoot()).isZero();
    }

    @Test
    void theNumberOfFilesIsBounded() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 1);
        write(sink.open("a", ""), TinyMedia.png());
        assertThatThrownBy(() -> sink.open("b", ""))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("1");
    }

    @Test
    void withNoMediaRootItFailsClosed() {
        var sink = new ReceivedFileSink(null, "run-1", MediaPolicy.defaults(), 4);
        assertThatThrownBy(() -> sink.open("a", ""))
                .isInstanceOf(A2AException.Refused.class)
                .hasMessageContaining("media_root");
    }

    @Test
    void aRunIdThatCouldBuildAPathIsRejected() {
        assertThatThrownBy(() -> new ReceivedFileSink(root, "../x", MediaPolicy.defaults(), 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingTwiceDoesNotRecordTwice() throws IOException {
        var sink = sink(MediaPolicy.defaults(), 4);
        var out = sink.open("a", "");
        out.write(TinyMedia.png());
        out.close();
        out.close();
        assertThat(sink.files()).hasSize(1);
    }
}
