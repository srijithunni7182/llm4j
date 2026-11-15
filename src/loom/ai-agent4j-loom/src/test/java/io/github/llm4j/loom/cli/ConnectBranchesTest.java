package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.execution.McpDiscovery;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The refusals, choices and edge cases of {@code weave connect} that a person meets (phase 36d, G86 to G88). */
class ConnectBranchesTest {

    static final Path PETSTORE = Path.of("../../tools/extras/ai-agent4j-tools-openapi/src/test/resources/fixtures/petstore3/openapi.json");

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env(String answer) {
        return new WeaveEnv(m -> null, q -> answer, new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> {}, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
    }

    private Path script(String extra) throws Exception {
        Files.copy(PETSTORE, dir.resolve("petstore.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path file = dir.resolve("w.loom");
        Files.writeString(file, extra + "workflow Main() { note \"x\" }\n");
        return file;
    }

    private ConnectCommand.OpenApi openapi(Path script) {
        ConnectCommand.OpenApi c = new ConnectCommand.OpenApi();
        c.spec = "petstore.json";
        c.write = script == null ? null : script.toFile();
        c.prefix = "Pet";
        return c;
    }

    // ── choosing ──────────────────────────────────────────────────────────────────────────

    @Test
    void aPersonChoosesByNumberByReadsOrByAll() throws Exception {
        ConnectCommand.OpenApi c = openapi(null);
        c.spec = dir.resolve("petstore.json").toString();
        Files.copy(PETSTORE, dir.resolve("petstore.json"));

        assertThat(ConnectCommand.OpenApi.run(c, env("1"))).isZero();
        assertThat(out.toString()).contains("tool Pet_").contains("Nothing is written");
        out.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env("reads"))).isZero();
        assertThat(out.toString()).contains("effect: reads").doesNotContain("effect: changes");
        out.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env(" ALL "))).isZero();
        assertThat(out.toString()).contains("effect: reads").contains("effect: changes");
        out.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env("1, 2"))).isZero();
        assertThat(out.toString().split("\n")).filteredOn(l -> l.startsWith("tool ")).hasSize(2);
    }

    @Test
    void anAnswerThatIsNotAChoiceStopsTheCommand() throws Exception {
        ConnectCommand.OpenApi c = openapi(null);
        c.spec = PETSTORE.toString();

        assertThat(ConnectCommand.OpenApi.run(c, env("banana"))).isEqualTo(2);
        assertThat(err.toString()).contains("banana is not one of the numbers");
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env("999"))).isEqualTo(2);
        assertThat(err.toString()).contains("999 is not one of the numbers");
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env(""))).isEqualTo(2);
        assertThat(err.toString()).contains("nothing was chosen");
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(c, env(null))).isEqualTo(2);
    }

    @Test
    void anUnknownOperationAnEmptyChoiceABadPrefixAndAnUnreadableSpecAreRefused() throws Exception {
        Path script = script("");
        ConnectCommand.OpenApi unknown = openapi(script);
        unknown.operations = List.of("noSuchOperation");
        assertThat(ConnectCommand.OpenApi.run(unknown, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("no operation noSuchOperation").contains("getPetById");

        ConnectCommand.OpenApi empty = openapi(script);
        empty.operations = List.of("addPet");
        empty.readOnly = true;
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(empty, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("no operation was chosen");

        ConnectCommand.OpenApi prefix = openapi(script);
        prefix.prefix = "bad prefix!";
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(prefix, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("--prefix");

        ConnectCommand.OpenApi missing = openapi(script);
        missing.spec = "nowhere.json";
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(missing, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("not a file");

        ConnectCommand.OpenApi noScript = openapi(script);
        noScript.write = dir.resolve("absent.loom").toFile();
        noScript.readOnly = true;
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(noScript, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("could not be read");
    }

    @Test
    void aWrittenDeclarationHasTheCommentTheHeaderAndTheRetryRuleOfItsMethod() throws Exception {
        Path script = script("");
        ConnectCommand.OpenApi c = openapi(script);
        c.operations = List.of("getPetById", "deletePet");
        c.auth = "env.PET_TOKEN";
        c.authHeader = "X-Api-Key";
        c.yes = true;
        WeaveEnv withToken = env("yes").withEnvLookup(k -> k.equals("PET_TOKEN") ? "test-token-000" : null);

        assertThat(ConnectCommand.OpenApi.run(c, withToken)).isZero();

        String written = Files.readString(script);
        assertThat(written).contains("// GET /pet/{petId}").contains("auth_header: \"X-Api-Key\"").contains("auth_value: env.PET_TOKEN");
        assertThat(written.split("tool Pet_deletePet")[1]).contains("effect: changes").contains("on_unknown: skip");
        assertThat(written.split("tool Pet_getPetById")[1].split("}")[0]).contains("on_unknown: retry");
        assertThat(written).doesNotContain("test-token-000");
    }

    @Test
    void locationsAreWrittenAsAScriptNamesThem() {
        assertThat(ConnectCommand.OpenApi.locationFor("https://x.example/a.json", dir)).isEqualTo("https://x.example/a.json");
        assertThat(ConnectCommand.OpenApi.locationFor("specs/a.json", dir)).isEqualTo("specs/a.json");
        assertThat(ConnectCommand.OpenApi.locationFor("../outside.json", dir)).isEqualTo(dir.resolve("../outside.json").normalize().toString());
    }

    // ── refresh ───────────────────────────────────────────────────────────────────────────

    @Test
    void aRefreshOfAScriptWithNothingToCompareIsRefusedAndAnUnpinnedToolGetsAHint() throws Exception {
        Path none = script("");
        ConnectCommand.OpenApi refresh = new ConnectCommand.OpenApi();
        refresh.spec = none.toString();
        refresh.refresh = true;
        refresh.check = true;
        assertThat(ConnectCommand.OpenApi.run(refresh, env("no"))).isEqualTo(2);
        assertThat(err.toString()).contains("declares no openapi tool");

        Path unpinned = script("tool Pet_a { use: openapi  spec: \"petstore.json\"  operation: \"getPetById\"  effect: reads }\ntool Pet_b { use: openapi  spec: \"petstore.json\"  operation: \"getInventory\"  effect: reads  pin: \"sha256-0000000000000000\" }\n");
        refresh.spec = unpinned.toString();
        out.reset();
        assertThat(ConnectCommand.OpenApi.run(refresh, env("no"))).isEqualTo(1);
        assertThat(out.toString()).contains("hint: Pet_a has no pin").contains("Pet_b: the signature of getInventory changed");

        refresh.spec = dir.resolve("absent.loom").toString();
        err.reset();
        assertThat(ConnectCommand.OpenApi.run(refresh, env("no"))).isEqualTo(2);
    }

    @Test
    void aRefreshReportsADescriptionThatCannotBeReadAndAnOperationThatIsGone() throws Exception {
        Path script = script("tool Pet_a { use: openapi  spec: \"gone.json\"  operation: \"getPetById\"  effect: reads  pin: \"sha256-0000000000000000\" }\ntool Pet_b { use: openapi  spec: \"petstore.json\"  operation: \"removedOperation\"  effect: reads  pin: \"sha256-0000000000000000\" }\n");
        ConnectCommand.OpenApi refresh = new ConnectCommand.OpenApi();
        refresh.spec = script.toString();
        refresh.refresh = true;
        refresh.check = false;

        assertThat(ConnectCommand.OpenApi.run(refresh, env("no"))).isEqualTo(1);

        assertThat(out.toString()).contains("Pet_a: gone.json could not be read now").contains("Pet_b: removedOperation is no longer an operation").contains("never rewrites a pin by itself");
    }

    // ── mcp ───────────────────────────────────────────────────────────────────────────────

    private ConnectCommand.Mcp mcp(Path script) {
        List<String> command = FakeMcpServer.command();
        ConnectCommand.Mcp c = new ConnectCommand.Mcp();
        c.target = command.get(0);
        c.args = new ArrayList<>(command.subList(1, command.size()));
        c.name = "Fake";
        c.write = script == null ? null : script.toFile();
        return c;
    }

    @Test
    void anMcpBadNameABadCredentialReferenceAndACheckWithoutRefreshAreRefused() throws Exception {
        ConnectCommand.Mcp name = mcp(null);
        name.name = "bad name";
        assertThat(ConnectCommand.Mcp.run(name, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("--name");

        ConnectCommand.Mcp auth = mcp(null);
        auth.target = "https://mcp.example.com/mcp";
        auth.auth = "sk-test-000";
        err.reset();
        assertThat(ConnectCommand.Mcp.run(auth, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("never the credential itself").doesNotContain("sk-test-000");
        auth.auth = "invoker.token";
        auth.listAuth = "also a value";
        err.reset();
        assertThat(ConnectCommand.Mcp.run(auth, env("yes"))).isEqualTo(2);

        ConnectCommand.Mcp check = mcp(null);
        check.check = true;
        err.reset();
        assertThat(ConnectCommand.Mcp.run(check, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("--check goes with --refresh");
    }

    @Test
    void aServerThatCannotBeStartedIsReportedAndNothingIsWritten() throws Exception {
        Path script = script("");
        String before = Files.readString(script);
        ConnectCommand.Mcp c = mcp(script);
        c.target = "no-such-program-for-the-test";
        c.args = new ArrayList<>();

        assertThat(ConnectCommand.Mcp.run(c, env("yes"))).isEqualTo(1);

        assertThat(err.toString()).contains("no-such-program-for-the-test");
        assertThat(Files.readString(script)).isEqualTo(before);
    }

    @Test
    void onlyTheNamedToolsGetAnEffectAndReadOnlyPassesWhenTheyRead() throws Exception {
        Path script = script("");
        ConnectCommand.Mcp c = mcp(script);
        c.operations = List.of("read");
        c.readOnly = true;
        c.yes = true;

        assertThat(ConnectCommand.Mcp.run(c, env("yes"))).isZero();

        String written = Files.readString(script);
        assertThat(written).contains("effects: { read: reads }").doesNotContain("echo: ");
        assertThat(out.toString()).contains("(not declared)");
    }

    @Test
    void aBlockWithTheSameNameIsRefused() throws Exception {
        Path script = script("mcp Fake { transport: \"stdio\"  cmd: \"x\"  isolation: process }\n");
        ConnectCommand.Mcp c = mcp(script);
        c.yes = true;

        assertThat(ConnectCommand.Mcp.run(c, env("yes"))).isEqualTo(2);
        assertThat(err.toString()).contains("already has a block named Fake");
    }

    @Test
    void theBlockIsWrittenForAContainerAndForARemoteServerWithOnlyReferences() {
        McpDiscovery.Result found = new McpDiscovery.Result("Box", "npx", "container", List.of(), "sha256-0123456789abcdef", null, List.of());
        ConnectCommand.Mcp container = new ConnectCommand.Mcp();
        container.name = "Box";
        container.target = "npx";
        container.args = List.of("-y", "a \"quoted\" arg");
        container.isolation = "container";
        container.image = "node:22";
        container.workspace = "data";
        container.network = "open";
        Map<String, TaskEffect> effects = new LinkedHashMap<>();
        effects.put("read_file", TaskEffect.READS);
        effects.put("odd name", TaskEffect.CHANGES);

        String text = ConnectCommand.Mcp.block(container, found, effects, false);

        assertThat(text).contains("mcp Box {").contains("transport: \"stdio\"").contains("cmd: \"npx\"").contains("args: [\"-y\", \"a \\\"quoted\\\" arg\"]").contains("isolation: container").contains("image: \"node:22\"").contains("workspace: \"data\"").contains("network: open").contains("effects: { read_file: reads, \"odd name\": changes }").contains("pin: \"sha256-0123456789abcdef\"");

        ConnectCommand.Mcp remote = new ConnectCommand.Mcp();
        remote.name = "Remote";
        remote.target = "https://mcp.example.com/mcp";
        remote.auth = "invoker.token";
        remote.listAuth = "env.LIST_TOKEN";
        String remoteText = ConnectCommand.Mcp.block(remote, found, Map.of(), true);
        assertThat(remoteText).contains("transport: \"http\"").contains("url: \"https://mcp.example.com/mcp\"").contains("auth: invoker.token").contains("list_auth: env.LIST_TOKEN").doesNotContain("effects:").doesNotContain("cmd:");
    }

    @Test
    void anMcpRefreshNeedsAPinnedBlockAndReportsAChangedToolList() throws Exception {
        Path none = script("");
        ConnectCommand.Mcp refresh = new ConnectCommand.Mcp();
        refresh.target = none.toString();
        refresh.refresh = true;
        refresh.check = true;
        assertThat(ConnectCommand.Mcp.run(refresh, env("no"))).isEqualTo(2);
        assertThat(err.toString()).contains("declares no pinned mcp block");

        List<String> command = FakeMcpServer.command();
        List<String> quoted = new ArrayList<>();
        for (String a : command.subList(1, command.size())) quoted.add("\"" + a + "\"");
        Path pinned = script("mcp Fake { transport: \"stdio\"  cmd: \"" + command.get(0) + "\"  args: [" + String.join(", ", quoted) + "]  isolation: process  pin: \"sha256-0000000000000000\" }\nmcp Loose { transport: \"stdio\"  cmd: \"x\"  isolation: process }\n");
        refresh.target = pinned.toString();
        out.reset();

        assertThat(ConnectCommand.Mcp.run(refresh, env("no"))).isEqualTo(1);

        assertThat(out.toString()).contains("hint: mcp Loose has no pin").contains("mcp Fake: the tool list changed");

        Path gone = script("mcp Gone { transport: \"stdio\"  cmd: \"no-such-program-for-the-test\"  isolation: process  pin: \"sha256-0000000000000000\" }\n");
        refresh.target = gone.toString();
        out.reset();
        assertThat(ConnectCommand.Mcp.run(refresh, env("no"))).isEqualTo(1);
        assertThat(out.toString()).contains("mcp Gone: could not be started or listed now");

        refresh.target = dir.resolve("absent.loom").toString();
        assertThat(ConnectCommand.Mcp.run(refresh, env("no"))).isEqualTo(2);
    }

    @Test
    void aServerDefinitionIsReadFromTheCommandLineOptionsOfAContainerRun() {
        McpServerDef def = new McpServerDef("Box");
        def.setIsolation("container");
        def.setImage("node:22");
        assertThat(def.getImage()).isEqualTo("node:22");
    }
}
