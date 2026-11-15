package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.generate.GateResult;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.Severity;
import io.github.llm4j.mcp.testkit.FakeMcpServer;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The time-to-first-safe-call benchmark (phase 36, D17), in the form the default build can repeat: {@code
 * weave connect} run non-interactively on recorded fixtures (and a scripted model for the two model-assisted
 * systems), each result held to the same measures: how many approvals it asked for, whether the script it wrote
 * passes {@code weave check}, what the audit says about the new declarations, and, where a command is run,
 * whether the first real call succeeded. The runs against the real systems, with a real model, are not made here;
 * {@code docs/benchmark/TIME_TO_FIRST_SAFE_CALL.md} says which are and which are not.
 */
class ConnectBenchmarkTest {

    static final Path FIXTURES = Path.of("src/test/resources/benchmark");
    static final Path PETSTORE = Path.of("../../tools/extras/ai-agent4j-tools-openapi/src/test/resources/fixtures/petstore3/openapi.json");

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final List<String> asked = new ArrayList<>();

    /** What one run measured. */
    record Row(String system, int approvals, boolean checkPasses, int findings, Severity highest, boolean firstCallSucceeded) {}

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> {
            asked.add(q);
            return "yes";
        }, new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> {}, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
    }

    private Path script(String extra) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Files.writeString(dir.resolve("work/notes.txt"), "one\ntwo\nthree\n");
        Path file = dir.resolve("w.loom");
        Files.writeString(file, "tool Data { use: workspace  root: \"work\"  allow: \"**\"  mode: readwrite }\n" + extra + "workflow Main() { note \"x\" }\n");
        return file;
    }

    private Row measure(String system, Path script, Set<String> added, boolean firstCall) throws Exception {
        String written = Files.readString(script);
        ConnectEdit.Verdict v = ConnectEdit.gates(script, written, added, env());
        Severity highest = v.findings().stream().map(Finding::severity).min(Severity::compareTo).orElse(null);
        return new Row(system, asked.size(), v.passed(), v.findings().size(), highest, firstCall);
    }

    private static LLMClient scripted(String answer) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content(answer).model("scripted").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void aRestApiWithAPublishedDescription() throws Exception {
        Files.copy(PETSTORE, dir.resolve("petstore.json"));
        Path script = script("");
        ConnectCommand.OpenApi c = new ConnectCommand.OpenApi();
        c.spec = "petstore.json";
        c.write = script.toFile();
        c.prefix = "Pet";
        c.readOnly = true;

        int code = ConnectCommand.OpenApi.run(c, env());
        Row row = measure("openapi: Swagger Petstore (recorded description), reading operations", script, Set.of("Pet_getPetById", "Pet_findPetsByStatus", "Pet_findPetsByTags", "Pet_getInventory", "Pet_getOrderById", "Pet_getUserByName", "Pet_loginUser", "Pet_logoutUser"), false);

        assertThat(code).isZero();
        assertThat(row.approvals()).as("one approval for the whole change").isEqualTo(1);
        assertThat(row.checkPasses()).isTrue();
        assertThat(row.highest()).as("nothing above medium").isIn(null, Severity.MEDIUM, Severity.LOW, Severity.INFO);
        assertThat(Files.readString(script)).as("no manual edit was needed for the reads").doesNotContain("TODO");
    }

    @Test
    void aLocalMcpServerOverStdio() throws Exception {
        Path script = script("");
        List<String> command = FakeMcpServer.command();
        ConnectCommand.Mcp c = new ConnectCommand.Mcp();
        c.target = command.get(0);
        c.args = new ArrayList<>(command.subList(1, command.size()));
        c.name = "Fake";
        c.write = script.toFile();

        int code = ConnectCommand.Mcp.run(c, env());
        Row row = measure("mcp: a stdio server (the testkit's), isolation process", script, Set.of("Fake"), true);

        assertThat(code).isZero();
        assertThat(row.approvals()).isEqualTo(1);
        assertThat(row.checkPasses()).isTrue();
        assertThat(Files.readString(script)).contains("isolation: process").contains("pin: \"sha256-");
        assertThat(out.toString()).as("the listing is the first call, and it worked").contains("lists");
    }

    @Test
    void aCommandLineToolFromItsHelpText() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = new ConnectDraft.CommandForm();
        f.name = "Count";
        f.write = script.toFile();
        f.helpFile = FIXTURES.resolve("wc/help.txt").toFile();
        f.program = "wc";
        f.isolation = "process";
        f.network = "open";
        f.workspaceTool = "Data";
        f.model = "scripted";
        f.samples = FIXTURES.resolve("wc/samples.json").toFile();
        f.budget = 100_000;
        f.repairs = 2;

        int code = ConnectDraft.run(f, env(), scripted(Files.readString(FIXTURES.resolve("wc/answer.json"))));
        Row row = measure("command: GNU wc (real help text, scripted model), isolation process", script, Set.of("Count"), code == 0);

        assertThat(code).as(err.toString()).isZero();
        assertThat(row.approvals()).isEqualTo(1);
        assertThat(row.checkPasses()).isTrue();
        assertThat(row.firstCallSucceeded()).as("the person's sample ran the real program and passed").isTrue();
        assertThat(Files.readString(script)).contains("run: \"wc {mode} {file}\"").contains("isolation: process");
    }

    @Test
    void anApiKnownOnlyFromItsDocumentation() throws Exception {
        Path script = script("");
        ConnectDraft.HttpForm f = new ConnectDraft.HttpForm();
        f.name = "Orders";
        f.write = script.toFile();
        f.docs = FIXTURES.resolve("orders-docs/docs.md").toFile();
        f.baseUrl = "https://api.example.com";
        f.auth = "invoker.token";
        f.model = "scripted";
        f.budget = 100_000;
        f.repairs = 2;

        int code = ConnectDraft.run(f, env(), scripted(Files.readString(FIXTURES.resolve("orders-docs/answer.json"))));
        Row row = measure("http: a documentation excerpt (hand-written, scripted model)", script, Set.of("Orders"), false);

        assertThat(code).as(err.toString()).isZero();
        assertThat(row.approvals()).isEqualTo(1);
        assertThat(row.checkPasses()).isTrue();
        assertThat(Files.readString(script)).contains("methods: \"GET\"").contains("auth_value: invoker.token");
    }

    @Test
    void everyBenchmarkFixtureHasMetadataListingItsFilesAndTheScanFindsNothingInThem() throws Exception {
        var scan = new io.github.llm4j.tools.ScanKind().create("Scan", java.util.Map.of(), Path.of("."));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (Stream<Path> folders = Files.list(FIXTURES)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                var meta = json.readTree(Files.readString(folder.resolve("METADATA.json")));
                for (String key : List.of("what", "service", "licence", "recorded", "scrubbed", "files")) assertThat(meta.hasNonNull(key)).as(folder.getFileName() + " has " + key).isTrue();
                java.util.Set<String> listed = new java.util.HashSet<>();
                meta.get("files").fieldNames().forEachRemaining(listed::add);
                try (Stream<Path> files = Files.list(folder)) {
                    java.util.Set<String> present = new java.util.HashSet<>();
                    for (Path file : files.filter(p -> !p.getFileName().toString().equals("METADATA.json")).toList()) {
                        present.add(file.getFileName().toString());
                        String result = scan.execute(java.util.Map.of("text", Files.readString(file)));
                        assertThat(json.readTree(result).get("count").asInt()).as(file + ": " + result).isZero();
                    }
                    assertThat(present).as(folder.getFileName() + " files").isEqualTo(listed);
                }
            }
        }
    }
}
