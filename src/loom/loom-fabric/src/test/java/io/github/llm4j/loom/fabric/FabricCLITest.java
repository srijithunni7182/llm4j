package io.github.llm4j.loom.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FabricCLITest {

    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        FabricCLI.Env e = new FabricCLI.Env();
        e.out = new PrintStream(out);
        e.err = new PrintStream(err);
        e.getenv = env::get;
        e.block = () -> {};
        return FabricCLI.run(e, args);
    }

    private static final Map<String, String> PW =
            Map.of("LOOM_FABRIC_ADMIN_PASSWORD", "correct horse battery");

    @Test
    void initCreatesTheVaultAndAMasterKeyFile_andClientsAreAddedListedAndRevoked()
            throws Exception {
        Path data = dir.resolve("data");
        assertThat(run(PW, "init", "--data", data.toString())).isZero();
        assertThat(Files.exists(data.resolve("master.key"))).isTrue();
        assertThat(Files.readString(data.resolve("vault.json"))).doesNotContain("correct horse");

        out.reset();
        assertThat(run(Map.of(), "clients", "add", "getviral-prod", "--data", data.toString()))
                .isZero();
        String key =
                out.toString()
                        .lines()
                        .filter(l -> l.contains("lfk_"))
                        .findFirst()
                        .orElseThrow()
                        .trim();
        assertThat(
                        Vault.open(
                                        data.resolve("vault.json"),
                                        Files.readString(data.resolve("master.key")).trim())
                                .get("client.getviral-prod"))
                .isEqualTo(key);

        out.reset();
        run(Map.of(), "clients", "list", "--data", data.toString());
        assertThat(out.toString()).contains("getviral-prod").doesNotContain("lfk_");

        assertThat(run(Map.of(), "clients", "add", "getviral-prod", "--data", data.toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("already");

        assertThat(run(Map.of(), "clients", "revoke", "getviral-prod", "--data", data.toString()))
                .isZero();
        assertThat(run(Map.of(), "clients", "revoke", "getviral-prod", "--data", data.toString()))
                .isEqualTo(2);
    }

    @Test
    void aShortAdminPasswordOrABadClientNameIsRefused() {
        assertThat(
                        run(
                                Map.of("LOOM_FABRIC_ADMIN_PASSWORD", "short"),
                                "init",
                                "--data",
                                dir.resolve("d").toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("12 characters");
        run(PW, "init", "--data", dir.resolve("e").toString());
        assertThat(
                        run(
                                Map.of(),
                                "clients",
                                "add",
                                "bad name!",
                                "--data",
                                dir.resolve("e").toString()))
                .isEqualTo(2);
    }

    @Test
    void commandsOnADirectoryWithoutAVaultSayWhatToDo() {
        assertThat(run(Map.of(), "clients", "list", "--data", dir.resolve("none").toString()))
                .isEqualTo(2);
        assertThat(err.toString()).contains("fabric init");
    }

    @Test
    void theMasterKeyCanComeFromTheEnvironmentInsteadOfAFile() throws Exception {
        Path data = dir.resolve("data");
        Map<String, String> env =
                Map.of(
                        "LOOM_FABRIC_ADMIN_PASSWORD",
                        "correct horse battery",
                        "LOOM_FABRIC_MASTER_KEY",
                        "an-environment-master-key-1");
        assertThat(run(env, "init", "--data", data.toString())).isZero();
        assertThat(Files.exists(data.resolve("master.key"))).isFalse();
        assertThat(run(env, "clients", "add", "a", "--data", data.toString())).isZero();
        assertThat(run(Map.of(), "clients", "list", "--data", data.toString()))
                .isEqualTo(2); // no key given
    }

    @Test
    void serveStartsAndPrintsWhereItListens() {
        Path data = dir.resolve("data");
        run(PW, "init", "--data", data.toString());
        out.reset();
        assertThat(run(Map.of(), "serve", "--data", data.toString(), "--port", "0")).isZero();
        assertThat(out.toString()).contains("Fabric is listening on http://127.0.0.1:");
    }

    @Test
    void serveRefusesAPublicAddressWithoutTls() {
        Path data = dir.resolve("data");
        run(PW, "init", "--data", data.toString());
        assertThat(
                        run(
                                Map.of(),
                                "serve",
                                "--data",
                                data.toString(),
                                "--bind",
                                "0.0.0.0",
                                "--port",
                                "0"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("without TLS");
    }

    @Test
    void tlsInitWritesTheFilesAndServeUsesThemAndPrintsThePin() throws Exception {
        Path tls = dir.resolve("tls");
        assertThat(
                        run(
                                Map.of(),
                                "tls",
                                "init",
                                "--host",
                                "fabric.test",
                                "--also",
                                "localhost,127.0.0.1",
                                "--out",
                                tls.toString()))
                .isZero();
        assertThat(out.toString()).contains("--observe-ca").contains("sha256:");
        Path data = dir.resolve("data");
        run(PW, "init", "--data", data.toString());
        out.reset();
        assertThat(
                        run(
                                Map.of(),
                                "serve",
                                "--data",
                                data.toString(),
                                "--port",
                                "0",
                                "--tls-keystore",
                                tls.resolve("server.p12").toString()))
                .isZero();
        assertThat(out.toString()).contains("https://127.0.0.1:").contains("Server key pin");
    }

    @Test
    void sizesAreParsed() {
        assertThat(FabricCLI.Serve.bytes("2GB")).isEqualTo(2L << 30);
        assertThat(FabricCLI.Serve.bytes("500mb")).isEqualTo(500L << 20);
        assertThat(FabricCLI.Serve.bytes("1000")).isEqualTo(1000);
    }
}
