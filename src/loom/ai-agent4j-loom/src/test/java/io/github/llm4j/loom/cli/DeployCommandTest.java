package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.deploy.DeployTest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R25, R29, R30 of spec 15: a warning and never a gate, no overwrite of edits, and verification.
 */
class DeployCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    private Out deploy(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("deploy calls no model");
                        },
                        q -> {
                            throw new IllegalStateException("nobody to ask");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        DeployCommand c = new DeployCommand();
        new CommandLine(c).parseArgs(args);
        int code = DeployCommand.run(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path script(String text) throws Exception {
        Path p = dir.resolve("main.loom");
        Files.writeString(p, text);
        return p;
    }

    private static final String RISKY =
            "agent Replier { model: \"gemini-2.5-flash\"  system: \"You reply to customers.\"  tools: [Hook] }\n"
                    + "tool Hook { use: webhook  url: env.HOOK_URL }\n"
                    + "workflow Main(customer_email) {\n  delegate \"Write to {customer_email}\" to Replier -> reply\n  note \"{reply}\"\n}\n";

    @Test
    void writesTheFilesAndASecondRunChangesNothing() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Path out = dir.resolve("deploy");
        Out first = deploy(s.toString(), "--target", "compose", "--out", out.toString());
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(out.resolve("compose.yaml")).exists();
        assertThat(out.resolve("Dockerfile")).exists();
        assertThat(out.resolve("README.md")).exists();
        assertThat(Files.isExecutable(out.resolve("entrypoint.sh"))).isTrue();
        assertThat(first.out()).contains("Nothing was built or started");
        assertThat(deploy(s.toString(), "--out", out.toString()).out())
                .contains("already up to date");
    }

    @Test
    void dryRunPrintsAndWritesNothing() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Out r = deploy(s.toString(), "--dry-run", "--out", dir.resolve("deploy").toString());
        assertThat(r.out()).contains("=== Dockerfile ===").contains("=== compose.yaml ===");
        assertThat(Files.exists(dir.resolve("deploy"))).isFalse();
    }

    @Test
    void aHandEditedFileIsKeptUntilForced() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Path out = dir.resolve("deploy");
        deploy(s.toString(), "--out", out.toString());
        Path compose = out.resolve("compose.yaml");
        Files.writeString(compose, Files.readString(compose) + "# my change\n");
        Files.writeString(s, DeployTest.SCRIPT.replace("gemini-2.5-flash", "gemini-2.5-pro"));
        Out kept = deploy(s.toString(), "--out", out.toString());
        assertThat(kept.out()).contains("Kept your edits in compose.yaml");
        assertThat(Files.readString(compose)).endsWith("# my change\n");
        assertThat(deploy(s.toString(), "--out", out.toString(), "--force").out())
                .contains("Wrote");
        assertThat(Files.readString(compose)).doesNotContain("# my change");
    }

    @Test
    void verifyPassesThenFlagsStaleMissingAndALostName() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Path out = dir.resolve("deploy");
        deploy(s.toString(), "--out", out.toString());
        assertThat(deploy(s.toString(), "--verify", out.toString()).code()).isZero();
        // the script gains a key the files do not name
        Files.writeString(s, DeployTest.SCRIPT.replace("gpt-4.1-mini", "grok-4"));
        Out stale = deploy(s.toString(), "--verify", out.toString());
        assertThat(stale.code()).isEqualTo(1);
        assertThat(stale.err()).contains("stale:");
        // an edited compose that dropped a name the script needs
        deploy(s.toString(), "--out", out.toString());
        Path compose = out.resolve("compose.yaml");
        Files.writeString(compose, Files.readString(compose).replace("      - HOOK_URL\n", ""));
        assertThat(deploy(s.toString(), "--verify", out.toString()).err())
                .contains("no longer names HOOK_URL");
        Files.delete(out.resolve("README.md"));
        assertThat(deploy(s.toString(), "--verify", out.toString()).err())
                .contains("missing: README.md");
    }

    @Test
    void openFindingsAreAWarningAndNeverStopTheFiles() throws Exception {
        Path s = script(RISKY);
        Path out = dir.resolve("deploy");
        Out r = deploy(s.toString(), "--out", out.toString());
        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.err()).contains("weave review").contains("not blocking");
        assertThat(Files.readString(out.resolve("README.md")))
                .contains("Open findings")
                .contains("high");
    }

    @Test
    void requireCleanIsTheOptInGate() throws Exception {
        Path s = script(RISKY);
        Out r = deploy(s.toString(), "--out", dir.resolve("deploy").toString(), "--require-clean");
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("--require-clean");
        assertThat(Files.exists(dir.resolve("deploy"))).isFalse();
        Path clean = script(DeployTest.SCRIPT);
        assertThat(
                        deploy(
                                        clean.toString(),
                                        "--out",
                                        dir.resolve("d2").toString(),
                                        "--require-clean")
                                .code())
                .isZero();
    }

    @Test
    void anUnsafeFabricBindAndBadOptionsAreRefusedBeforeAnythingIsWritten() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Path out = dir.resolve("deploy");
        Out bind = deploy(s.toString(), "--out", out.toString(), "--fabric-publish", "0.0.0.0");
        assertThat(bind.code()).isEqualTo(2);
        assertThat(bind.err()).contains("TLS");
        assertThat(deploy(s.toString(), "--target", "swarm").err()).contains("--target");
        assertThat(deploy(s.toString(), "--journal", "disk").err()).contains("--journal");
        assertThat(deploy(dir.resolve("none.loom").toString()).err()).contains("is not there");
        assertThat(Files.exists(out)).isFalse();
    }

    @Test
    void aScriptThatDoesNotCheckGeneratesNothing() throws Exception {
        Path s = script("workflow Main(x) {\n    delegate \"hi\" to Nobody -> r\n}\n");
        Out r = deploy(s.toString(), "--out", dir.resolve("deploy").toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("deploy"))).isFalse();
    }

    @Test
    void dockerTargetHasNoFabricOrCompose() throws Exception {
        Path s = script(DeployTest.SCRIPT);
        Path out = dir.resolve("deploy");
        deploy(s.toString(), "--target", "docker", "--out", out.toString());
        assertThat(out.resolve("compose.yaml")).doesNotExist();
        assertThat(Files.readString(out.resolve("entrypoint.sh")))
                .doesNotContain("--observe-fabric");
    }

    @Test
    void requireCleanStopsWhenTheReviewCouldNotRunAsWellAsWhenItFoundSomething() {
        var clean = new DeployCommand.Review(null, "No findings were open.", 0, true);
        var high = new DeployCommand.Review("w", "s", 2, true);
        var unknown = new DeployCommand.Review(null, "The review could not run.", 0, false);
        assertThat(DeployCommand.blocks(true, clean)).isFalse();
        assertThat(DeployCommand.blocks(true, high)).isTrue();
        assertThat(DeployCommand.blocks(true, unknown)).isTrue();
        assertThat(DeployCommand.blocks(false, unknown)).isFalse();
    }
}
