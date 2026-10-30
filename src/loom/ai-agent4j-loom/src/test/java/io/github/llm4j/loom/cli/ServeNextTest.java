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

/** {@code loom/next} answers with what {@code weave next --format json} prints, fresh every time. */
class ServeNextTest {

    @TempDir Path dir;

    ServeClient client;
    Path main;

    @BeforeEach
    void start() throws Exception {
        assertThat(WeaveCLI.commandLine().execute("init", "pipeline", dir.resolve("p").toString(), "--flat")).isZero();
        main = dir.resolve("p").resolve("main.loom");
        client = new ServeClient(new Server(new CliEngine(new ResultCache(64L << 20), "test"), 20000));
        ObjectNode init = ServeClient.params();
        init.putArray("roots").add(dir.toString());
        client.request("initialize", init);
    }

    @AfterEach
    void stop() throws Exception {
        client.close();
    }

    private JsonNode next() throws Exception {
        return client.request("loom/next", ServeClient.params().put("file", main.toString())).path("result");
    }

    @Test
    void itAnswersWithTheTenStagesAndThePrimaryStep() throws Exception {
        JsonNode result = next().path("result");

        assertThat(result.path("stages")).hasSize(10);
        assertThat(result.path("primary").path("id").asText()).isNotBlank();
        assertThat(next().path("cached").asBoolean(true)).isFalse();
    }

    @Test
    void aResultRecordedOnDiskShowsAtOnceBecauseNothingIsCached() throws Exception {
        assertThat(next().path("result").path("preflight").path("state").asText()).isEqualTo("none");

        Files.createDirectories(main.resolveSibling(".loom"));
        Files.writeString(main.resolveSibling(".loom/preflight.json"), "{\"mock\":true,\"at\":\"2026-10-29T10:00:00Z\"}");

        assertThat(next().path("result").path("preflight").path("state").asText()).isEqualTo("mock-only");
    }

    @Test
    void anUnsavedBufferIsWhatIsChecked() throws Exception {
        ObjectNode p = ServeClient.params().put("file", main.toString());
        p.put("text", "workflow Main( {");
        p.put("version", 3);

        JsonNode result = client.request("loom/next", p).path("result");

        assertThat(result.path("result").path("stages").get(3).path("state").asText()).isEqualTo("failing");
    }
}
