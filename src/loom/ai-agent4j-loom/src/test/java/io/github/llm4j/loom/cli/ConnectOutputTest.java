package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What {@code weave connect} says, and the small functions it is built from (phase 36d). */
class ConnectOutputTest {

    static final Path PETSTORE = Path.of("../../tools/extras/ai-agent4j-tools-openapi/src/test/resources/fixtures/petstore3/openapi.json");

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env(String answer) {
        return new WeaveEnv(m -> null, q -> answer, new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> {}, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
    }

    @Test
    void theAnswerOfAModelIsOneObjectWhateverSurroundsIt() {
        assertThat(ConnectDraft.extractJson("{\"a\": 1}")).isEqualTo("{\"a\": 1}");
        assertThat(ConnectDraft.extractJson("text {\"a\": 1} text")).isEqualTo("{\"a\": 1}");
        assertThat(ConnectDraft.extractJson("```json\n{\"a\": {\"b\": 2}}\n```")).isEqualTo("{\"a\": {\"b\": 2}}");
        assertThat(ConnectDraft.extractJson("see:\n```\n{\"a\": 1}\n```\nthanks {not json}")).isEqualTo("{\"a\": 1}");
        assertThat(ConnectDraft.extractJson("```json\n{\"a\": 1}")).as("an unclosed fence").isEqualTo("{\"a\": 1}");
        assertThatThrownBy(() -> ConnectDraft.extractJson("no braces here")).hasMessageContaining("no JSON object");
        assertThatThrownBy(() -> ConnectDraft.extractJson("} then {")).hasMessageContaining("no JSON object");
        assertThatThrownBy(() -> ConnectDraft.extractJson(null)).hasMessageContaining("no JSON object");
        assertThat(ConnectDraft.extractJson("{}")).isEqualTo("{}");
    }

    @Test
    void longTextIsCutAtThreeHundredCharactersAndShortTextIsNot() {
        assertThat(ConnectDraft.abbreviate("short")).isEqualTo("short");
        assertThat(ConnectDraft.abbreviate("x".repeat(300))).hasSize(300);
        assertThat(ConnectDraft.abbreviate("x".repeat(301))).hasSize(301).endsWith("…");
        assertThat(ConnectCommand.OpenApi.oneLine("a   b\n c")).isEqualTo("a b c");
        assertThat(ConnectCommand.OpenApi.oneLine("x".repeat(100))).hasSize(100);
        assertThat(ConnectCommand.OpenApi.oneLine("x".repeat(101))).hasSize(103).endsWith("...");
    }

    @Test
    void appendKeepsOneBlankLineBetweenTheScriptAndWhatIsAdded() {
        assertThat(ConnectEdit.append("a\n", "b\n")).isEqualTo("a\n\nb\n");
        assertThat(ConnectEdit.append("a", "b")).isEqualTo("a\n\nb\n");
    }

    @Test
    void theListingTheTableAndTheDryRunAreSaidInOrder() throws Exception {
        ConnectCommand.OpenApi c = new ConnectCommand.OpenApi();
        c.spec = PETSTORE.toString();
        c.prefix = "Pet";

        assertThat(ConnectCommand.OpenApi.run(c, env("2"))).isZero();

        String text = out.toString();
        assertThat(text).contains("Operations in Swagger Petstore").contains("   2  POST   /pet ").contains("addPet");
        assertThat(text.indexOf("Operations in")).isLessThan(text.indexOf("with the effect each tool would get"));
        assertThat(text.indexOf("with the effect")).isLessThan(text.indexOf("Nothing is written (give --write <script>). The declarations would be:"));
        assertThat(text).containsPattern("  POST   /pet +changes +addPet");
    }

    @Test
    void theAuditIsSaidWhenItHasSomethingToSayAndWhenItHasNot() throws Exception {
        ConnectEdit.printFindings(new ConnectEdit.Verdict(List.of(), List.of(), false), env("yes"));
        assertThat(out.toString()).contains("nothing to say about the new declarations");
        out.reset();
        var finding = new io.github.llm4j.loom.security.Finding("LA31", io.github.llm4j.loom.security.Severity.MEDIUM, List.of(), "tool Py", 3, "A program runs without a sandbox", "risk", "fix");
        ConnectEdit.printFindings(new ConnectEdit.Verdict(List.of(), List.of(finding), false), env("yes"));
        assertThat(out.toString()).contains("The audit, on the new declarations:").contains("LA31 MEDIUM  tool Py: A program runs without a sandbox");
    }

    @Test
    void aFailedGateListsEveryProblemAndAWrittenChangeSaysSo() throws Exception {
        Path script = dir.resolve("w.loom");
        Files.writeString(script, "workflow Main() { note \"x\" }\n");
        String before = Files.readString(script);

        int refused = ConnectEdit.apply(script, ConnectEdit.append(before, "tool A { use: openapi }\ntool B { use: openapi }\n"), ConnectEdit.append(before, "tool A { use: openapi }\ntool B { use: openapi }\n"), java.util.Set.of("A", "B"), true, env("yes"));
        assertThat(refused).isEqualTo(1);
        assertThat(err.toString()).contains("does not pass weave check").contains("tool A").contains("tool B");

        out.reset();
        String after = ConnectEdit.append(before, "tool Calc { use: compute }\n");
        assertThat(ConnectEdit.apply(script, before, after, java.util.Set.of("Calc"), true, env("yes"))).isZero();
        assertThat(out.toString()).contains("The change to w.loom:").contains("+ tool Calc { use: compute }").contains("Written.");
        assertThat(Files.readString(script)).isEqualTo(after);
    }

    @Test
    void theCommandsRunThroughTheCliWithTheirOptions() throws Exception {
        Path script = dir.resolve("w.loom");
        Files.writeString(script, "workflow Main() { note \"x\" }\n");
        Files.copy(PETSTORE, dir.resolve("petstore.json"));
        PrintStream before = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            assertThat(WeaveCLI.run("connect")).isZero();
            assertThat(captured.toString()).contains("Usage: connect").contains("openapi").contains("mcp").contains("command").contains("http");
            captured.reset();
            assertThat(WeaveCLI.run("connect", "openapi", dir.resolve("petstore.json").toString(), "--operations", "getPetById")).isZero();
            assertThat(captured.toString()).contains("tool Api_getPetById").contains("effect: reads");
            captured.reset();
            assertThat(WeaveCLI.run("connect", "mcp", "--help")).isZero();
            assertThat(captured.toString()).contains("--isolation").contains("--refresh");
            captured.reset();
            assertThat(WeaveCLI.run("connect", "command", "Say", "--help-file", dir.resolve("petstore.json").toString(), "--isolation", "process", "--network", "open", "--workspace-tool", "Data", "--write", script.toString())).isEqualTo(2);
        } finally {
            System.setOut(before);
        }
    }

    @Test
    void aDraftThatRepeatsTheSpendCapStopsAfterTheCallThatReachedIt() throws Exception {
        Path script = dir.resolve("w.loom");
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(script, "tool Data { use: workspace  root: \"work\"  allow: \"**\"  mode: readwrite }\nworkflow Main() { note \"x\" }\n");
        int[] calls = {0};
        LLMClient expensive = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                calls[0]++;
                return LLMResponse.builder().content("not json").model("t").tokenUsage(40, 20, 60).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        ConnectDraft.CommandForm f = new ConnectDraft.CommandForm();
        f.name = "Say";
        f.write = script.toFile();
        f.helpFile = Files.writeString(dir.resolve("help.txt"), "printf").toFile();
        f.isolation = "process";
        f.network = "open";
        f.workspaceTool = "Data";
        f.model = "t";
        f.budget = 50;
        f.repairs = 5;

        assertThat(ConnectDraft.run(f, env("yes"), expensive)).isEqualTo(4);

        assertThat(calls[0]).as("the second call was never made").isEqualTo(1);
        assertThat(err.toString()).contains("spend cap").contains("Nothing was written");
    }

    @Test
    void aCredentialIsLookedUpAsARunLooksItUp() {
        var store = new io.github.llm4j.secret.InMemorySecretStore();
        store.put("FROM_STORE", "test-store-value");
        WeaveEnv env = env("yes").withSecrets(store).withEnvLookup(k -> k.equals("FROM_ENV") ? "test-env-value" : null);

        var lookup = ConnectCommand.Mcp.credentials(env);

        assertThat(lookup.apply("secret:FROM_STORE")).isEqualTo("test-store-value");
        assertThat(lookup.apply("secret:ABSENT")).isNull();
        assertThat(lookup.apply("FROM_STORE")).as("a plain name the store holds").isEqualTo("test-store-value");
        assertThat(lookup.apply("FROM_ENV")).isEqualTo("test-env-value");
        assertThat(lookup.apply("ABSENT")).isNull();
        assertThat(ConnectCommand.Mcp.credentials(env("yes").withEnvLookup(k -> "from-env")).apply("secret:X")).as("no store").isNull();
    }

    @Test
    void anMcpServerGivenOnTheCommandLineIsListedWithItsBlock() throws Exception {
        List<String> command = io.github.llm4j.mcp.testkit.FakeMcpServer.command();
        List<String> args = new java.util.ArrayList<>(List.of("connect", "mcp", command.get(0), "--name", "Fake"));
        for (String a : command.subList(1, command.size())) {
            args.add("--arg");
            args.add(a);
        }
        PrintStream before = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
        try {
            assertThat(WeaveCLI.run(args.toArray(String[]::new))).isZero();
        } finally {
            System.setOut(before);
        }
        assertThat(captured.toString()).contains("mcp Fake {").contains("isolation: process").contains("Nothing is written");
    }

    @Test
    void aDraftWithNoModelConfiguredIsRefusedBeforeAnythingIsAsked() throws Exception {
        Path script = dir.resolve("w.loom");
        Files.writeString(script, "tool Data { use: workspace  root: \"work\"  allow: \"**\"  mode: readwrite }\nworkflow Main() { note \"x\" }\n");
        Path help = Files.writeString(dir.resolve("help.txt"), "printf");
        PrintStream before = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            assertThat(WeaveCLI.run("connect", "command", "Say", "--help-file", help.toString(), "--isolation", "process", "--network", "open", "--workspace-tool", "Data", "--write", script.toString())).isEqualTo(2);
        } finally {
            System.setErr(before);
        }
        assertThat(captured.toString()).contains("no model to draft with");
    }

    @Test
    void anAnswerWithAFenceButNoBodyIsStillFoundByItsBraces() {
        assertThat(ConnectDraft.extractJson("```{\"a\":1}```")).isEqualTo("{\"a\":1}");
        assertThat(ConnectDraft.extractJson("```\n```{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(ConnectDraft.extractJson("``` {\"a\":1}\n{\"b\":2} ```")).isEqualTo("{\"b\":2}");
        assertThatThrownBy(() -> ConnectDraft.extractJson("{")).hasMessageContaining("no JSON object");
        assertThatThrownBy(() -> ConnectDraft.extractJson("}{")).hasMessageContaining("no JSON object");
    }
}
