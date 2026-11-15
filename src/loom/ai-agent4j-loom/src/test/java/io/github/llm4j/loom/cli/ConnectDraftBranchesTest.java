package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

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

/** The refusals and the repair path of the model-assisted {@code weave connect} forms (phase 36d, G86 and G87). */
class ConnectDraftBranchesTest {

    @TempDir Path dir;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> "yes", new PrintStream(out, true), new PrintStream(err, true), Clock.systemUTC(), d -> {}, c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"));
    }

    static LLMClient answering(String... answers) {
        int[] next = {0};
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                return LLMResponse.builder().content(answers[Math.min(next[0]++, answers.length - 1)]).model("t").build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private Path script(String extra) throws Exception {
        Files.createDirectories(dir.resolve("work"));
        Path file = dir.resolve("w.loom");
        Files.writeString(file, "tool Data { use: workspace  root: \"work\"  allow: \"**\"  mode: readwrite }\n" + extra + "workflow Main() { note \"x\" }\n");
        return file;
    }

    private ConnectDraft.CommandForm command(Path script) throws Exception {
        ConnectDraft.CommandForm f = new ConnectDraft.CommandForm();
        f.name = "Say";
        f.write = script == null ? null : script.toFile();
        f.helpFile = Files.writeString(dir.resolve("help.txt"), "printf FORMAT").toFile();
        f.program = "printf";
        f.isolation = "process";
        f.network = "open";
        f.workspaceTool = "Data";
        f.model = "t";
        f.yes = true;
        f.budget = 100_000;
        f.repairs = 1;
        return f;
    }

    static final String GOOD = "{\"run\": \"printf %s {text}\", \"params\": {\"text\": \"string\"}}";

    @Test
    void everyFlagProblemIsNamedBeforeTheModelIsAsked() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = command(null);
        assertThat(f.flagProblem()).contains("--write");
        f = command(script);
        f.workspaceTool = null;
        assertThat(f.flagProblem()).contains("--workspace-tool");
        f = command(script);
        f.isolation = "vm";
        assertThat(f.flagProblem()).contains("never run without one");
        f = command(script);
        f.isolation = "container";
        f.image = " ";
        assertThat(f.flagProblem()).contains("--image");
        f = command(script);
        f.network = "allow";
        assertThat(f.flagProblem()).contains("--network");
        assertThat(command(script).flagProblem()).isNull();
        assertThat(command(script).kind()).isEqualTo("command");

        ConnectDraft.HttpForm h = new ConnectDraft.HttpForm();
        h.baseUrl = "https://api.example.com";
        assertThat(h.flagProblem()).contains("--write");
        h.write = script.toFile();
        h.baseUrl = "ftp://x";
        assertThat(h.flagProblem()).contains("https address");
        h.baseUrl = "https://api.example.com";
        h.auth = "token-value";
        assertThat(h.flagProblem()).contains("never the credential itself");
        h.auth = "secret.TOKEN";
        assertThat(h.flagProblem()).isNull();
        assertThat(h.kind()).isEqualTo("http");
    }

    @Test
    void aBadToolNameANameInUseAndAWorkspaceToolThatIsNotOneAreRefused() throws Exception {
        Path script = script("tool Other { use: compute }\n");
        ConnectDraft.CommandForm bad = command(script);
        bad.name = "not a name";
        assertThat(ConnectDraft.run(bad, env(), answering(GOOD))).isEqualTo(2);
        assertThat(err.toString()).contains("letters, digits and underscores");

        ConnectDraft.CommandForm used = command(script);
        used.name = "Other";
        err.reset();
        assertThat(ConnectDraft.run(used, env(), answering(GOOD))).isEqualTo(2);
        assertThat(err.toString()).contains("already has a tool Other");

        ConnectDraft.CommandForm notWorkspace = command(script);
        notWorkspace.workspaceTool = "Other";
        err.reset();
        assertThat(ConnectDraft.run(notWorkspace, env(), answering(GOOD))).isEqualTo(2);
        assertThat(err.toString()).contains("not a workspace tool");

        ConnectDraft.CommandForm absent = command(script);
        absent.workspaceTool = "Nope";
        err.reset();
        assertThat(ConnectDraft.run(absent, env(), answering(GOOD))).isEqualTo(2);
        assertThat(err.toString()).contains("no workspace tool Nope");

        ConnectDraft.CommandForm noScript = command(script);
        noScript.write = dir.resolve("absent.loom").toFile();
        err.reset();
        assertThat(ConnectDraft.run(noScript, env(), answering(GOOD))).isEqualTo(2);
    }

    @Test
    void aSamplesFileThatIsNotAListAndAnUnreadableHelpFileAreRefused() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = command(script);
        f.samples = Files.writeString(dir.resolve("samples.json"), "{\"args\": {}}").toFile();
        assertThat(ConnectDraft.run(f, env(), answering(GOOD))).isEqualTo(2);
        assertThat(err.toString()).contains("must hold a JSON list");

        f = command(script);
        f.helpFile = dir.resolve("absent.txt").toFile();
        err.reset();
        assertThat(ConnectDraft.run(f, env(), answering(GOOD))).isEqualTo(2);
    }

    @Test
    void theAnswerMayBeFencedOrSurroundedByTextAndIsStillOneObject() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = command(script);
        f.samples = Files.writeString(dir.resolve("samples.json"), "[{\"args\": {\"text\": \"hi\"}, \"expect_contains\": \"hi\"}]").toFile();

        assertThat(ConnectDraft.run(f, env(), answering("Here you go:\n```json\n" + GOOD + "\n```\nHope it helps."))).isZero();
        assertThat(Files.readString(script)).contains("tool Say {");
    }

    @Test
    void aDraftWithABadShapeIsRepairedFromTheMessageOfEachProblem() throws Exception {
        Path script = script("");
        List<String> shapes =
                List.of(
                        "{\"params\": {}}",
                        "{\"run\": \"printf %s {text}\", \"params\": {\"bad name\": \"string\", \"text\": \"string\"}}",
                        "{\"run\": \"printf %s {text}\", \"params\": {\"text\": \"string\"}, \"output\": \"xml\"}",
                        "{\"run\": \"printf %s {other}\", \"params\": {\"text\": \"string\"}}");
        for (String shape : shapes) {
            ConnectDraft.CommandForm f = command(script);
            f.repairs = 0;
            err.reset();
            assertThat(ConnectDraft.run(f, env(), answering(shape))).as(shape).isEqualTo(3);
            assertThat(err.toString()).as(shape).contains("nothing was written");
        }
        assertThat(Files.readString(script)).doesNotContain("tool Say");
    }

    @Test
    void aSampleThatTheToolRefusesOrThatDoesNotMatchIsAFailureNamingWhy() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = command(script);
        f.repairs = 0;
        f.samples = Files.writeString(dir.resolve("samples.json"), "[{\"args\": {\"nope\": \"x\"}}]").toFile();

        assertThat(ConnectDraft.run(f, env(), answering(GOOD))).isEqualTo(3);

        assertThat(err.toString()).contains("sample 1 failed").contains("unknown argument nope");
        assertThat(Files.readString(script)).doesNotContain("tool Say");
    }

    @Test
    void aDraftThatDoesNotBuildAsAToolIsAFailure() throws Exception {
        Path script = script("");
        ConnectDraft.CommandForm f = command(script);
        f.repairs = 0;

        assertThat(ConnectDraft.run(f, env(), answering("{\"run\": \"printf %s {text}\", \"params\": {\"text\": \"colour\"}}"))).isEqualTo(3);

        assertThat(err.toString()).contains("the draft is not a valid command tool");
    }

    @Test
    void anHttpDraftNeedsMethodsAndPathsAndAnUnknownEffectCountsAsChanges() throws Exception {
        Path script = script("");
        ConnectDraft.HttpForm f = new ConnectDraft.HttpForm();
        f.name = "Orders";
        f.write = script.toFile();
        f.docs = Files.writeString(dir.resolve("docs.md"), "GET /orders").toFile();
        f.baseUrl = "https://api.example.com";
        f.model = "t";
        f.yes = true;
        f.budget = 100_000;
        f.repairs = 0;

        assertThat(ConnectDraft.run(f, env(), answering("{\"allow_paths\": [\"/orders\"]}"))).isEqualTo(3);
        assertThat(ConnectDraft.run(f, env(), answering("{\"methods\": [\"GET\"]}"))).isEqualTo(3);
        err.reset();
        assertThat(ConnectDraft.run(f, env(), answering("{\"methods\": [\"GET\"], \"allow_paths\": [\"/orders\"], \"effect\": \"explodes\"}"))).isZero();
        assertThat(out.toString()).contains("effect changes");
    }
}
