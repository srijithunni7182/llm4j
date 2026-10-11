package io.github.llm4j.loom.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FabricOptionsTest {

    @TempDir
    Path dir;

    private static final Function<String, String> NO_ENV = k -> null;

    private ObserveOptions parse(String observe, String name, String url, Function<String, String> env) {
        return ObserveOptions.fromCommandLine(observe, null, null, name, null, url, null, false, null, null, null, null, env);
    }

    @Test
    void withNeitherFlagNorEnvironmentNothingIsObserved() {
        assertThat(parse(null, null, null, NO_ENV)).isNull();
    }

    @Test
    void aFabricUrlNeedsAName_thereIsNoDefault() {
        assertThatThrownBy(() -> parse(null, null, "https://fabric.example.com:9090", NO_ENV)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--observe-name");
    }

    @Test
    void theUrlCanComeFromTheEnvironment_andFabricAloneDoesNotServeAPage() {
        ObserveOptions o = parse(null, "getviral-prod", null, Map.of("LOOM_FABRIC_URL", "https://fabric.example.com:9090")::get);
        assertThat(o.embedded()).isFalse();
        assertThat(o.fabric().url().getHost()).isEqualTo("fabric.example.com");
        assertThat(o.fabric().keySecret()).isEqualTo("LOOM_FABRIC_CLIENT_KEY");
        assertThat(o.name()).isEqualTo("getviral-prod");
    }

    @Test
    void bothTogetherServeThePageAndReport() {
        ObserveOptions o = parse("8787", "getviral-prod", "https://f.example.com", NO_ENV);
        assertThat(o.embedded()).isTrue();
        assertThat(o.fabric()).isNotNull();
        assertThat(parse("", null, null, NO_ENV).fabric()).isNull();
    }

    @Test
    void plainHttpIsOnlyForLoopback() {
        assertThat(FabricOptions.parse("http://127.0.0.1:9090", "p", null, false, null, null, null, null).url().getPort()).isEqualTo(9090);
        assertThatThrownBy(() -> FabricOptions.parse("http://fabric.example.com", "p", null, false, null, null, null, null)).hasMessageContaining("https");
        assertThatThrownBy(() -> FabricOptions.parse("ftp://x", "p", null, false, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FabricOptions.parse("https://user:pw@x.example.com", "p", null, false, null, null, null, null)).hasMessageContaining("user name");
    }

    @Test
    void aCaFileMustExist_andCaOnlyNeedsOne_andCaAndPinAreExclusive() throws Exception {
        Path ca = Files.writeString(dir.resolve("ca.pem"), "x");
        assertThat(FabricOptions.parse("https://x.example.com", "p", ca.toString(), true, null, null, null, null).caOnly()).isTrue();
        assertThatThrownBy(() -> FabricOptions.parse("https://x.example.com", "p", dir.resolve("none.pem").toString(), false, null, null, null, null)).hasMessageContaining("no such file");
        assertThatThrownBy(() -> FabricOptions.parse("https://x.example.com", "p", null, true, null, null, null, null)).hasMessageContaining("--observe-ca");
        String pin = "sha256:" + "ab".repeat(32);
        assertThatThrownBy(() -> FabricOptions.parse("https://x.example.com", "p", ca.toString(), false, pin, null, null, null)).hasMessageContaining("not both");
    }

    @Test
    void aPinIsNormalisedAndChecked() {
        String hex = "AB".repeat(32);
        assertThat(FabricOptions.parse("https://x.example.com", "p", null, false, "sha256:" + hex, null, null, null).pin()).isEqualTo(hex.toLowerCase());
        assertThatThrownBy(() -> FabricOptions.parse("https://x.example.com", "p", null, false, "sha256:abc", null, null, null)).hasMessageContaining("64 hex");
    }

    @Test
    void theNameIsChecked_andTheFlushTimeoutParsed() {
        assertThatThrownBy(() -> FabricOptions.parse("https://x.example.com", "bad name!", null, false, null, null, null, null)).hasMessageContaining("letters");
        assertThat(FabricOptions.parse("https://x.example.com", "p", null, false, null, null, null, "10s").flushTimeout().getSeconds()).isEqualTo(10);
    }

    @Test
    void anOtlpEndpointNeedsAName_https_andWellFormedHeaders_andCanBeUsedAlone() {
        assertThatThrownBy(() -> OtlpOptions.parse("https://c.example.com:4318", List.of(), null)).hasMessageContaining("--observe-name");
        assertThatThrownBy(() -> OtlpOptions.parse("http://c.example.com", List.of(), "p")).hasMessageContaining("https");
        assertThatThrownBy(() -> OtlpOptions.parse("https://c.example.com", List.of("no equals sign"), "p")).hasMessageContaining("Name=value");
        assertThat(OtlpOptions.parse("http://127.0.0.1:4318", List.of("Authorization=Bearer ${secret:OTLP_TOKEN}"), "p").headers()).hasSize(1);
        ObserveOptions o = ObserveOptions.fromCommandLine(null, null, null, "p", null, null, null, false, null, null, null, null, "https://c.example.com:4318", List.of("X-Team=a"), NO_ENV);
        assertThat(o.embedded()).isFalse();
        assertThat(o.fabric()).isNull();
        assertThat(o.otlp().endpoint().getHost()).isEqualTo("c.example.com");
    }
}
