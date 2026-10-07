package web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.loom.eval.MockModels;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web host on models that cost nothing: the page's three calls, and the rule that nothing is saved before a person says yes. */
class WebHostTest {

    @TempDir Path out;

    private HttpServer server;
    private Session session;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        System.setProperty("web.output", out.toString());
        session = new Session(Path.of("src", "main", "resources", "main.loom"), new MockModels());
        server = App.start(session, 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        System.clearProperty("web.output");
    }

    private int post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void waitFor(String status) throws Exception {
        for (int i = 0; i < 200 && !session.status().equals(status); i++) Thread.sleep(50);
        assertThat(session.status()).isEqualTo(status);
    }

    @Test
    void theArticleIsSavedOnlyAfterThePersonSaysYes() throws Exception {
        assertThat(post("/api/run", "{\"topic\": \"Home Composting\"}")).isEqualTo(200);
        waitFor("waiting");
        assertThat(session.state().get("question").toString()).contains("Approve this article?");
        assertThat(out).as("nothing is saved while a person has not answered").isEmptyDirectory();

        assertThat(post("/api/answer", "{\"answer\": \"yes\"}")).isEqualTo(200);
        waitFor("done");

        assertThat(out.resolve("home-composting.md")).exists();
        assertThat(session.state().get("result").toString()).contains("Saved to");
        assertThat(session.state().get("calls")).as("the page shows what the run cost").isNotNull();
    }

    @Test
    void anAnswerOfNoSavesNothing() throws Exception {
        post("/api/run", "{\"topic\": \"x\"}");
        waitFor("waiting");
        post("/api/answer", "{\"answer\": \"no\"}");
        waitFor("done");
        assertThat(out).isEmptyDirectory();
        assertThat(session.state().get("result").toString()).contains("Not approved");
    }

    @Test
    void anEmptyTopicIsRefusedAndAnAnswerWithoutAQuestionIsIgnored() throws Exception {
        assertThat(post("/api/run", "{\"topic\": \" \"}")).isEqualTo(400);
        assertThat(post("/api/answer", "{\"answer\": \"yes\"}")).isEqualTo(409);
    }

    @Test
    void theFileNameComesFromTheTitle() {
        assertThat(SaveMarkdown.slug("  Home Composting: 5 tips!  ")).isEqualTo("home-composting-5-tips");
        assertThat(SaveMarkdown.slug("???")).isEqualTo("article");
    }
}
