package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code weave connect openapi} and {@code mcp} (phase 36d, D10; guards G86 to G88): a person chooses,
 * the effects are proposed and approved, the change is shown, and nothing is written that fails the
 * gates or holds a secret; {@code --refresh --check} reports drift and never writes.
 */
class ConnectCommandTest {

    static final Path PETSTORE = Path.of("../../tools/extras/ai-agent4j-tools-openapi/src/test/resources/fixtures/petstore3/openapi.json");

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final List<String> asked = new ArrayList<>();

    WeaveEnv env(String answer) {
        return new WeaveEnv(
                m -> null,
                question -> {
                    asked.add(question);
                    return answer;
                },
                new PrintStream(out, true),
                new PrintStream(err, true),
                Clock.systemUTC(),
                d -> {},
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""),
                List.of("weave"));
    }

    private Path script(String extra) throws Exception {
        Files.copy(PETSTORE, dir.resolve("petstore.json"));
        Path file = dir.resolve("w.loom");
        Files.writeString(file, extra + "workflow Main() { note \"x\" }\n");
        return file;
    }

    private ConnectCommand.OpenApi openapi(Path script) {
        ConnectCommand.OpenApi c = new ConnectCommand.OpenApi();
        c.spec = "petstore.json";
        c.write = script.toFile();
        c.prefix = "Pet";
        return c;
    }

    @Test
    void withoutWriteItOnlyPrintsWhatItWouldWrite() throws Exception {
        Path script = script("");
        String before = Files.readString(script);
        ConnectCommand.OpenApi c = openapi(script);
        c.write = null;
        c.spec = dir.resolve("petstore.json").toString();
        c.readOnly = true;

        assertThat(ConnectCommand.OpenApi.run(c, env("no"))).isZero();

        assertThat(out.toString()).contains("Nothing is written").contains("use: openapi").contains("effect: reads").contains("pin: \"sha256-");
        assertThat(Files.readString(script)).isEqualTo(before);
        assertThat(asked).isEmpty();
    }

    @Test
    void readOnlyWritesOneToolPerReadingOperationWithPinAndPassesCheck() throws Exception {
        Path script = script("");
        ConnectCommand.OpenApi c = openapi(script);
        c.readOnly = true;
        c.yes = true;
        c.auth = "invoker.pet_token";

        assertThat(ConnectCommand.OpenApi.run(c, env("no"))).isZero();

        String written = Files.readString(script);
        assertThat(written).contains("tool Pet_getPetById {").contains("operation: \"getPetById\"").contains("effect: reads").contains("auth_value: invoker.pet_token");
        assertThat(written).doesNotContain("effect: changes").doesNotContain("addPet");
        assertThat(out.toString()).contains("Written.").contains("The change to w.loom");
        assertThat(ConnectEdit.gates(script, written, java.util.Set.of(), env("no")).passed()).as("weave check on the result").isTrue();
    }

    @Test
    void anOperationThatChangesGetsChangesAndIsAskedAboutWhenNotApproved() throws Exception {
        Path script = script("");
        String before = Files.readString(script);
        ConnectCommand.OpenApi c = openapi(script);
        c.operations = List.of("addPet");

        assertThat(ConnectCommand.OpenApi.run(c, env("no"))).isZero();

        assertThat(asked).singleElement().asString().contains("effects as proposed");
        assertThat(out.toString()).contains("effect: changes").contains("on_unknown: skip").contains("Not written.");
        assertThat(Files.readString(script)).as("declined: nothing written").isEqualTo(before);

        assertThat(ConnectCommand.OpenApi.run(c, env("yes"))).isZero();
        assertThat(Files.readString(script)).contains("tool Pet_addPet {").contains("effect: changes");
    }

    @Test
    void aCredentialValueIsRefusedAndNothingIsWritten() throws Exception {
        Path script = script("");
        String before = Files.readString(script);
        ConnectCommand.OpenApi c = openapi(script);
        c.readOnly = true;
        c.yes = true;
        c.auth = "sk-test-000";

        assertThat(ConnectCommand.OpenApi.run(c, env("yes"))).isEqualTo(2);

        assertThat(err.toString()).contains("never the credential itself").doesNotContain("sk-test-000");
        assertThat(Files.readString(script)).isEqualTo(before);
    }

    @Test
    void aResultThatFailsTheCheckIsNeverWritten() throws Exception {
        Path script = script("");
        String before = Files.readString(script);

        int code = ConnectEdit.apply(script, before, ConnectEdit.append(before, "tool Broken { use: openapi }\n"), java.util.Set.of("Broken"), true, env("yes"));

        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("nothing was written");
        assertThat(Files.readString(script)).isEqualTo(before);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".connect-"));
        }
    }

    @Test
    void aNewDeclarationWithAHighAuditFindingIsNotWritten() throws Exception {
        Path script = script("tool Data { use: workspace  root: \"work\"  allow: \"**\"  mode: readwrite }\n");
        Files.createDirectories(dir.resolve("work"));
        String before = Files.readString(script);
        String risky = "tool Py { use: sandbox  workspace: Data  isolation: process  network: open  allow: \"ls\"  env.TOKEN: env.PYPI_TOKEN }\n";

        WeaveEnv withToken = env("yes").withEnvLookup(k -> k.equals("PYPI_TOKEN") ? "test-token-000" : null);
        int code = ConnectEdit.apply(script, before, ConnectEdit.append(before, risky), java.util.Set.of("Py"), true, withToken);

        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).contains("high audit finding");
        assertThat(Files.readString(script)).isEqualTo(before);
    }

    @Test
    void aNameAlreadyInTheScriptIsRefused() throws Exception {
        Path script = script("tool Pet_getPetById { use: openapi  spec: \"petstore.json\"  operation: \"getPetById\"  effect: reads }\n");
        ConnectCommand.OpenApi c = openapi(script);
        c.operations = List.of("getPetById");
        c.yes = true;

        assertThat(ConnectCommand.OpenApi.run(c, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("already has a tool Pet_getPetById");
    }

    @Test
    void refreshCheckReportsADriftedSignatureAndNeverWrites() throws Exception {
        Path script = script("");
        ConnectCommand.OpenApi c = openapi(script);
        c.operations = List.of("getPetById");
        c.yes = true;
        assertThat(ConnectCommand.OpenApi.run(c, env("yes"))).isZero();
        String written = Files.readString(script);

        ConnectCommand.OpenApi check = new ConnectCommand.OpenApi();
        check.spec = script.toString();
        check.refresh = true;
        check.check = true;
        assertThat(ConnectCommand.OpenApi.run(check, env("no"))).as("no drift yet").isZero();
        assertThat(out.toString()).contains("No drift");

        String spec = Files.readString(dir.resolve("petstore.json"));
        Files.writeString(dir.resolve("petstore.json"), spec.replace("\"/pet/{petId}\"", "\"/pets/{petId}\""));
        out.reset();
        assertThat(ConnectCommand.OpenApi.run(check, env("no"))).as("drift").isEqualTo(1);
        assertThat(out.toString()).contains("Pet_getPetById").contains("changed");
        assertThat(Files.readString(script)).as("a check writes nothing").isEqualTo(written);
    }

    @Test
    void checkWithoutRefreshIsRefused() {
        ConnectCommand.OpenApi c = new ConnectCommand.OpenApi();
        c.spec = "x";
        c.check = true;
        assertThat(ConnectCommand.OpenApi.run(c, env("no"))).isEqualTo(2);
    }

    // ── mcp ───────────────────────────────────────────────────────────────────────────────

    private ConnectCommand.Mcp mcp(Path script) {
        List<String> command = FakeMcpServer.command();
        ConnectCommand.Mcp c = new ConnectCommand.Mcp();
        c.target = command.get(0);
        c.args = new ArrayList<>(command.subList(1, command.size()));
        c.name = "Fake";
        c.write = script.toFile();
        return c;
    }

    @Test
    void anMcpServerIsStartedListedAndWrittenWithIsolationEffectsAndAPin() throws Exception {
        Path script = script("");
        ConnectCommand.Mcp c = mcp(script);
        c.yes = true;

        assertThat(ConnectCommand.Mcp.run(c, env("no"))).isZero();

        String written = Files.readString(script);
        assertThat(written).contains("mcp Fake {").contains("isolation: process").contains("effects: {").contains("pin: \"sha256-");
        assertThat(out.toString()).contains("untrusted");

        ConnectCommand.Mcp check = new ConnectCommand.Mcp();
        check.target = script.toString();
        check.refresh = true;
        check.check = true;
        out.reset();
        assertThat(ConnectCommand.Mcp.run(check, env("no"))).isZero();
        assertThat(out.toString()).contains("No drift");
    }

    @Test
    void readOnlyRefusesAServerWithToolsThatAreNotReads() throws Exception {
        Path script = script("");
        String before = Files.readString(script);
        ConnectCommand.Mcp c = mcp(script);
        c.readOnly = true;
        c.yes = true;

        int code = ConnectCommand.Mcp.run(c, env("no"));

        assertThat(code).as("the fake server lists tools with no read-only hint").isEqualTo(1);
        assertThat(err.toString()).contains("--read-only");
        assertThat(Files.readString(script)).isEqualTo(before);
    }
}
