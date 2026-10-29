package io.github.llm4j.loom.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.BuildIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code weave tools fetch}: downloads only what is named, after asking, with a checked checksum,
 * only for a release build, and only into the tools folder. It never runs what it downloads.
 */
class ToolsFetchTest {
    @TempDir Path tools;

    private static final String BASE =
            "https://repo1.maven.org/maven2/io/github/srijithunni7182/";

    /** A fake repository: URI to bytes, and what it says the checksums are. */
    private static final class FakeCentral implements ToolsFetch.Fetcher {
        final Map<String, byte[]> files = new java.util.HashMap<>();
        final List<String> asked = new ArrayList<>();

        FakeCentral add(String url, String content, boolean withSha256, boolean withSha1)
                throws Exception {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            files.put(url, bytes);
            if (withSha256) files.put(url + ".sha256", hex("SHA-256", bytes).getBytes());
            if (withSha1) files.put(url + ".sha1", hex("SHA-1", bytes).getBytes());
            return this;
        }

        static String hex(String alg, byte[] b) throws Exception {
            return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(b));
        }

        @Override
        public OptionalLong size(URI uri) {
            byte[] b = files.get(uri.toString());
            return b == null ? OptionalLong.empty() : OptionalLong.of(b.length);
        }

        @Override
        public Optional<byte[]> get(URI uri, long maxBytes) throws IOException {
            asked.add(uri.toString());
            return Optional.ofNullable(files.get(uri.toString()));
        }
    }

    private static BuildIdentity identity(String version, String kind) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().putValue("Implementation-Version", version);
        m.getMainAttributes().putValue("Build-Kind", kind);
        m.getMainAttributes().putValue("Build-Commit", "db5b43c7");
        return BuildIdentity.of(m);
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int fetch(
            ToolsFetch.Which which, BuildIdentity id, FakeCentral central, String answer, boolean yes) {
        return ToolsFetch.fetch(
                which, id, tools, central, m -> answer, yes, new PrintStream(out, true), new PrintStream(out, true));
    }

    private static final String FABRIC = BASE + "loom-fabric/6.1.0/loom-fabric-6.1.0-all.jar";

    @Test
    void aReleaseIsListedThenAskedThenDownloadedAndCheckedAndPlacedUnderTheVersion()
            throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", true, true);

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "yes", false);

        assertThat(code).isZero();
        Path jar = tools.resolve("6.1.0/loom-fabric-6.1.0-all.jar");
        assertThat(jar).exists().hasContent("fabric-bytes");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains(FABRIC)
                .contains("repo1.maven.org")
                .contains("SHA-256")
                .contains("FABRIC_JAR=" + jar);
    }

    @Test
    void saysNoToTheQuestionAndNothingIsDownloaded() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", true, true);

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "no", false);

        assertThat(code).isEqualTo(2);
        assertThat(central.asked).isEmpty();
        assertThat(tools.resolve("6.1.0")).doesNotExist();
    }

    @Test
    void yesSkipsTheQuestion() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", true, true);

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "no", true);

        assertThat(code).isZero();
    }

    @Test
    void aChecksumThatDoesNotMatchRefusesTheFileAndLeavesNothing() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", true, false);
        central.files.put(FABRIC + ".sha256", "0".repeat(64).getBytes());

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "yes", true);

        assertThat(code).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("does not match");
        try (var left = Files.exists(tools.resolve("6.1.0")) ? Files.list(tools.resolve("6.1.0")) : java.util.stream.Stream.<Path>empty()) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void whereCentralOffersOnlySha1TheOutputSaysSo() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", false, true);

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "yes", true);

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("SHA-1").contains("only");
    }

    @Test
    void noPublishedChecksumMeansNoInstall() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", false, false);

        int code = fetch(ToolsFetch.Which.FABRIC, identity("6.1.0", "release"), central, "yes", true);

        assertThat(code).isEqualTo(1);
        assertThat(tools.resolve("6.1.0/loom-fabric-6.1.0-all.jar")).doesNotExist();
    }

    @Test
    void aDevelopmentBuildIsRefusedBeforeAnythingIsAsked() throws Exception {
        FakeCentral central = new FakeCentral().add(FABRIC, "fabric-bytes", true, true);

        int code =
                fetch(
                        ToolsFetch.Which.FABRIC,
                        identity("6.2.0-SNAPSHOT", "development"),
                        central,
                        "yes",
                        true);

        assertThat(code).isEqualTo(2);
        assertThat(central.asked).isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("development build");
    }

    @Test
    void inspectFetchesBothItsJars() throws Exception {
        String inspect = BASE + "loom-inspect/6.1.0/loom-inspect-6.1.0.jar";
        String core = BASE + "loom-inspect-core/6.1.0/loom-inspect-core-6.1.0.jar";
        FakeCentral central = new FakeCentral().add(inspect, "i", true, false).add(core, "c", true, false);

        int code = fetch(ToolsFetch.Which.INSPECT, identity("6.1.0", "release"), central, "yes", true);

        assertThat(code).isZero();
        assertThat(tools.resolve("6.1.0/loom-inspect-6.1.0.jar")).exists();
        assertThat(tools.resolve("6.1.0/loom-inspect-core-6.1.0.jar")).exists();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("INSPECT_JAR=")
                .contains("INSPECT_CORE_JAR=");
    }

    @Test
    void theClassNeverStartsAProcessOrLoadsWhatItDownloaded() throws Exception {
        String source =
                Files.readString(
                        Path.of("src/main/java/io/github/llm4j/loom/install/ToolsFetch.java"));

        assertThat(source)
                .doesNotContain("ProcessBuilder")
                .doesNotContain("Runtime.getRuntime")
                .doesNotContain("URLClassLoader");
    }

    @Test
    void onlyMavenCentralOverHttpsIsEverAsked() {
        assertThat(ToolsFetch.allowed(URI.create(BASE + "x"))).isTrue();
        assertThat(ToolsFetch.allowed(URI.create("http://repo1.maven.org/maven2/x"))).isFalse();
        assertThat(ToolsFetch.allowed(URI.create("https://evil.example/maven2/x"))).isFalse();
        assertThat(ToolsFetch.allowed(URI.create("https://repo1.maven.org.evil.example/x"))).isFalse();
    }
}
