package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.serve.ResultCache;
import io.github.llm4j.loom.serve.ServeClient;
import io.github.llm4j.loom.serve.Server;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Spec 10 R20, R27: {@code loom/symbols} answers with what {@code weave symbols} prints. */
class ServeSymbolsTest {

    @TempDir Path dir;

    ServeClient client;
    Path main;

    @BeforeEach
    void start() throws Exception {
        main = dir.resolve("main.loom");
        Files.writeString(main, ServeEngineTest.TRIAGE);
        client =
                new ServeClient(
                        new Server(new CliEngine(new ResultCache(64L << 20), "test"), 20000));
        ObjectNode init = ServeClient.params();
        init.putArray("roots").add(dir.toString());
        client.request("initialize", init);
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
    }

    ObjectNode file(Path f) {
        return ServeClient.params().put("file", f.toString());
    }

    @Test
    void symbolsAnswersWithTheJsonOfWeaveSymbols() throws Exception {
        JsonNode reply = client.request("loom/symbols", file(main)).path("result");
        String cli =
                ServeEngineTest.stdoutOf(
                        env -> {
                            SymbolsCommand c = new SymbolsCommand();
                            new CommandLine(c).parseArgs(main.toString());
                            return SymbolsCommand.symbols(c, env);
                        });
        assertThat(reply.path("result")).isEqualTo(ServeEngineTest.JSON.readTree(cli));
        assertThat(reply.path("result").path("definitions")).isNotEmpty();
        assertThat(reply.path("result").path("version").asInt()).isEqualTo(1);
    }

    @Test
    void anUnsavedBufferIsWhatIsIndexedAndTheVersionComesBack() throws Exception {
        ObjectNode p = file(main);
        p.put("text", "agent Typed { model: \"m\" }\nworkflow W(x) {\n  delegate \"hi\" to Ty\n");
        p.put("version", 7);
        JsonNode reply = client.request("loom/symbols", p).path("result");
        assertThat(reply.path("requestVersion").asInt()).isEqualTo(7);
        JsonNode defs = reply.path("result").path("definitions");
        assertThat(defs).anyMatch(d -> d.path("name").asText().equals("Typed"));
        assertThat(defs).noneMatch(d -> d.path("name").asText().equals("Replier"));
        // half-typed, so partial: the problems say why
        assertThat(reply.path("result").path("partial").asBoolean()).isTrue();
    }

    @Test
    void theSecondAskIsCachedAndAnEditMakesItFresh() throws Exception {
        assertThat(
                        client.request("loom/symbols", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isFalse();
        assertThat(
                        client.request("loom/symbols", file(main))
                                .path("result")
                                .path("cached")
                                .asBoolean())
                .isTrue();
        Files.writeString(main, ServeEngineTest.TRIAGE + "agent Added { model: \"m\" }\n");
        JsonNode after = client.request("loom/symbols", file(main)).path("result");
        assertThat(after.path("cached").asBoolean()).isFalse();
        assertThat(after.path("result").path("definitions"))
                .anyMatch(d -> d.path("name").asText().equals("Added"));
    }

    @Test
    void aFileOutsideTheWorkspaceIsRefused() throws Exception {
        Path elsewhere = Files.createTempFile("outside", ".loom");
        try {
            JsonNode error = client.request("loom/symbols", file(elsewhere)).path("error");
            assertThat(error.path("data").path("kind").asText()).isEqualTo("outside-workspace");
        } finally {
            Files.deleteIfExists(elsewhere);
        }
    }
}
