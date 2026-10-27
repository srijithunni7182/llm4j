package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.RpcError;
import io.github.llm4j.loom.serve.ServeClient;
import io.github.llm4j.loom.serve.Server;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Spec 10, R13 to R18, R20-adjacent: {@code weave serve} answers with what the commands print, from
 * a cache that knows what each answer was made from, and stays inside the workspace.
 */
class ServeEngineTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String TRIAGE =
            """
            agent Replier { model: "gemini-2.5-flash"  system: "Answer the customer politely." }
            workflow Main(message) {
                delegate "Reply to: {message}" to Replier -> reply
                return reply
            }
            """;

    @TempDir Path dir;

    ResultCache cache;
    ServeClient client;
    Path main;

    @BeforeEach
    void start() throws Exception {
        main = dir.resolve("main.loom");
        Files.writeString(main, TRIAGE);
        cache = new ResultCache(64L << 20);
        client = new ServeClient(new Server(new CliEngine(cache, "test"), 20000));
        ObjectNode init = ServeClient.params();
        init.putArray("roots").add(dir.toString());
        client.request("initialize", init);
    }

    @org.junit.jupiter.api.AfterEach
    void stop() throws Exception {
        client.close();
    }

    ObjectNode file(Path f) {
        return ServeClient.params().put("file", f.toString());
    }

    static String stdoutOf(java.util.function.Function<WeaveEnv, Integer> command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("no models");
                        },
                        q -> "yes",
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        command.apply(env);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- the answers are the commands' answers

    @Test
    void checkAnswersWithTheJsonOfWeaveCheck() throws Exception {
        JsonNode reply = client.request("loom/check", file(main)).path("result");
        String cli =
                stdoutOf(
                        env ->
                                WeaveCLI.check(
                                        main.toFile(),
                                        null,
                                        new WeaveCLI.CheckSettings(false, true, true, false),
                                        env));
        assertThat(reply.path("result")).isEqualTo(JSON.readTree(cli));
        assertThat(reply.path("cached").asBoolean()).isFalse();
    }

    @Test
    void graphAnswersWithTheJsonOfWeaveGraph() throws Exception {
        JsonNode reply = client.request("loom/graph", file(main)).path("result");
        String cli =
                stdoutOf(
                        env -> {
                            GraphCommand c = new GraphCommand();
                            new CommandLine(c).parseArgs(main.toString());
                            return GraphCommand.graph(c, env);
                        });
        assertThat(reply.path("result")).isEqualTo(JSON.readTree(cli));
        assertThat(reply.path("result").path("workflows")).isNotEmpty();
    }

    @Test
    void reviewAnswersWithTheJsonOfWeaveReview() throws Exception {
        JsonNode reply = client.request("loom/review", file(main)).path("result");
        String cli =
                stdoutOf(
                        env -> {
                            ReviewCommand c = new ReviewCommand();
                            new CommandLine(c)
                                    .parseArgs(main.toString(), "--format", "json", "--no-env");
                            return ReviewCommand.review(c, env);
                        });
        assertThat(reply.path("result").path("summary"))
                .isEqualTo(JSON.readTree(cli).path("summary"));
        assertThat(reply.path("result").path("findings"))
                .isEqualTo(JSON.readTree(cli).path("findings"));
    }

    @Test
    void theInstantTierRunsTheValidityLensOnly() throws Exception {
        ObjectNode p = file(main).put("tier", "instant");
        JsonNode r = client.request("loom/review", p).path("result").path("result");
        for (JsonNode f : r.path("findings"))
            assertThat(f.path("lens").asText()).isEqualTo("validity");
    }

    @Test
    void explainRuleReturnsTheRulesPage() throws Exception {
        JsonNode r =
                client.request("loom/explainRule", ServeClient.params().put("rule", "LA01"))
                        .path("result");
        assertThat(r.path("text").asText()).contains("LA01");
        JsonNode bad =
                client.request("loom/explainRule", ServeClient.params().put("rule", "NOPE9"))
                        .path("error");
        assertThat(bad.path("data").path("kind").asText()).isEqualTo("engine-failure");
    }

    @Test
    void aScriptWithErrorsIsStillAnAnswerWithTheErrorsInIt() throws Exception {
        Files.writeString(main, "workflow Main(x) { delegate \"hi\" to Nobody -> r }\n");
        JsonNode r = client.request("loom/check", file(main)).path("result");
        assertThat(r.path("status").asInt()).isEqualTo(2);
        assertThat(r.path("result").toString()).contains("Nobody");
    }

    // ---- unsaved text

    @Test
    void theTextOfAnOpenBufferIsWhatIsChecked() throws Exception {
        JsonNode disk = client.request("loom/check", file(main)).path("result").path("result");
        String broken = "workflow Main(x) { delegate \"hi\" to Nobody -> r }\n";
        JsonNode buffer =
                client.request("loom/check", file(main).put("text", broken))
                        .path("result")
                        .path("result");
        assertThat(disk.toString()).doesNotContain("Nobody");
        assertThat(buffer.toString()).contains("Nobody");
        assertThat(Files.readString(main)).as("the file was not touched").isEqualTo(TRIAGE);
    }

    @Test
    void anImportedFileIsReadFromItsBufferToo() throws Exception {
        Files.writeString(
                dir.resolve("agents.loom"),
                "agent Replier { model: \"gemini-2.5-flash\"  system: \"x\" }\n");
        Files.writeString(
                main,
                "import \"agents.loom\"\nworkflow Main(m) { delegate \"hi\" to Replier -> r  return r }\n");
        JsonNode ok = client.request("loom/graph", file(main)).path("result").path("result");
        assertThat(ok.path("workflows")).isNotEmpty();
    }

    // ---- the cache

    @Test
    void theSameRequestTwiceIsAnsweredFromTheCache() throws Exception {
        client.request("loom/review", file(main));
        JsonNode second = client.request("loom/review", file(main)).path("result");
        assertThat(second.path("cached").asBoolean()).isTrue();
        assertThat(cache.hits()).isEqualTo(1);
    }

    @Test
    void anEditToTheScriptMakesTheNextAnswerFresh() throws Exception {
        client.request("loom/check", file(main));
        Files.writeString(main, TRIAGE + "\n// changed\n");
        JsonNode again = client.request("loom/check", file(main)).path("result");
        assertThat(again.path("cached").asBoolean()).isFalse();
    }

    @Test
    void aBufferWithTheSameTextAsTheFileStillHitsAndADifferentOneMisses() throws Exception {
        client.request("loom/check", file(main));
        assertThat(
                        client.request("loom/check", file(main).put("text", TRIAGE))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isTrue();
        assertThat(
                        client.request("loom/check", file(main).put("text", TRIAGE + "// x\n"))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void anImportedFileChangeInvalidatesItsDependentsOnly() throws Exception {
        Path agents = dir.resolve("agents.loom");
        Files.writeString(agents, "agent Replier { model: \"gemini-2.5-flash\"  system: \"x\" }\n");
        Files.writeString(
                main,
                "import \"agents.loom\"\nworkflow Main(m) { delegate \"hi\" to Replier -> r  return r }\n");
        Path other = dir.resolve("other.loom");
        Files.writeString(other, TRIAGE);
        client.request("loom/check", file(main));
        client.request("loom/check", file(other));

        Files.writeString(
                agents, "agent Replier { model: \"gemini-2.5-flash\"  system: \"changed\" }\n");

        assertThat(
                        client.request("loom/check", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .as("main imports the changed file")
                .isFalse();
        assertThat(
                        client.request("loom/check", file(other))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .as("other does not")
                .isTrue();
    }

    @Test
    void reviewPropertiesAndPromptFilesInvalidateTheReview() throws Exception {
        client.request("loom/review", file(main));
        Files.writeString(dir.resolve("review.properties"), "notice = off\n");
        assertThat(
                        client.request("loom/review", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
        client.request("loom/review", file(main));
        Files.createDirectories(dir.resolve("prompts"));
        Files.writeString(dir.resolve("prompts/replier.md"), "Be kind.");
        assertThat(
                        client.request("loom/review", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
        client.request("loom/review", file(main));
        Files.writeString(dir.resolve("prompts/replier.md"), "Be kinder.");
        assertThat(
                        client.request("loom/review", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
    }

    @Test
    void differentOptionsAreDifferentEntries() throws Exception {
        client.request("loom/review", file(main));
        assertThat(
                        client.request("loom/review", file(main).put("tier", "instant"))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
        assertThat(
                        client.request("loom/review", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isTrue();
    }

    @Test
    void theCacheStaysWithinItsMemoryBound() throws Exception {
        ResultCache small = new ResultCache(4000);
        try (ServeClient c = new ServeClient(new Server(new CliEngine(small, "t"), 20000))) {
            ObjectNode init = ServeClient.params();
            init.putArray("roots").add(dir.toString());
            c.request("initialize", init);
            for (int i = 0; i < 8; i++) {
                Path f = dir.resolve("s" + i + ".loom");
                Files.writeString(f, TRIAGE + "// " + i + "\n");
                c.request("loom/graph", file(f));
            }
            assertThat(small.bytes()).isLessThanOrEqualTo(4000L + 20000);
            assertThat(small.entries()).isLessThan(8);
        }
    }

    // ---- the workspace boundary

    @Test
    void aFileOutsideTheFoldersOpenedIsRefused() throws Exception {
        Path outside = Files.createTempFile("outside", ".loom");
        try {
            Files.writeString(outside, TRIAGE);
            JsonNode e = client.request("loom/check", file(outside)).path("error");
            assertThat(e.path("code").asInt()).isEqualTo(RpcError.OUTSIDE_WORKSPACE);
            assertThat(e.path("data").path("kind").asText()).isEqualTo("outside-workspace");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void dotDotCannotLeadOut() throws Exception {
        Path sneaky = dir.resolve("sub/../../escape.loom");
        JsonNode e = client.request("loom/check", file(sneaky)).path("error");
        assertThat(e.path("data").path("kind").asText()).isEqualTo("outside-workspace");
    }

    @Test
    void aLinkCannotLeadOut() throws Exception {
        Path target = Files.createTempFile("target", ".loom");
        try {
            Files.writeString(target, TRIAGE);
            Path link = dir.resolve("link.loom");
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | java.io.IOException e) {
                return; // no links here
            }
            JsonNode e = client.request("loom/check", file(link)).path("error");
            assertThat(e.path("data").path("kind").asText()).isEqualTo("outside-workspace");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aPriceFileOutsideTheFoldersIsRefusedToo() throws Exception {
        Path outside = Files.createTempFile("prices", ".properties");
        try {
            JsonNode e =
                    client.request("loom/review", file(main).put("prices", outside.toString()))
                            .path("error");
            assertThat(e.path("data").path("kind").asText()).isEqualTo("outside-workspace");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aMissingFileIsAnAnswerForCheckAndAnErrorForGraph() throws Exception {
        Path gone = dir.resolve("gone.loom");
        assertThat(client.request("loom/check", file(gone)).path("result").path("status").asInt())
                .isEqualTo(2);
        assertThat(
                        client.request("loom/graph", file(gone))
                                .path("error")
                                .path("data")
                                .path("kind")
                                .asText())
                .isEqualTo("engine-failure");
    }

    @Test
    void aRequestWithoutAFileIsInvalid() throws Exception {
        JsonNode e = client.request("loom/check", ServeClient.params()).path("error");
        assertThat(e.path("code").asInt()).isEqualTo(RpcError.INVALID_PARAMS);
    }
}
