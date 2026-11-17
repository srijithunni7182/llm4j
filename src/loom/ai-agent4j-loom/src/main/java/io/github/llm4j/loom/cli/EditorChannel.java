package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * How the Guide asks the editor to show something: open the graph, the review, the report, the
 * secret form, or a drafted instruction. The editor listens on loopback only and writes its port
 * and a per-session token to {@code .loom/guide/editor.json}; a request without the token is
 * refused there. When the editor is not open, the answer says so at once.
 */
interface EditorChannel {

    /** The editor's answer to one action, in words the Guide can pass on. */
    String send(String action, JsonNode args);

    /** The channel the running editor opened for this project. */
    static EditorChannel loopback(Path projectRoot) {
        return new Loopback(projectRoot);
    }

    /** The answer when no editor is listening. */
    String NOT_OPEN =
            "The editor is not open on this project (no .loom/guide/editor.json), so nothing was shown. Ask the developer to open the folder in VS Code with the Loom extension, or name the file or command for them instead.";

    final class Loopback implements EditorChannel {
        private static final Duration CONNECT = Duration.ofSeconds(2);
        /** A person may take a while with a form or an instruction; never longer than this. */
        private static final Duration ANSWER = Duration.ofMinutes(5);
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path file;
        private final HttpClient http;

        Loopback(Path projectRoot) {
            this.file = projectRoot.resolve(".loom").resolve("guide").resolve("editor.json");
            this.http = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public String send(String action, JsonNode args) {
            if (!Files.isRegularFile(file)) return NOT_OPEN;
            int port;
            String token;
            try {
                JsonNode e = JSON.readTree(file.toFile());
                port = e.path("port").asInt(0);
                token = e.path("token").asText("");
            } catch (IOException ex) {
                return NOT_OPEN;
            }
            if (port <= 0 || port > 65535 || token.isBlank()) return NOT_OPEN;
            ObjectNode body = JSON.createObjectNode();
            body.put("action", action);
            body.set("args", args == null ? JSON.createObjectNode() : args);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/guide"))
                        .timeout(ANSWER)
                        .header("Content-Type", "application/json")
                        .header("X-Loom-Guide-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 403) return "The editor refused the request (its token changed). Ask the developer to reload the window.";
                if (res.statusCode() != 200) return "The editor could not do it (status " + res.statusCode() + ").";
                return JSON.readTree(res.body()).path("result").asText("done");
            } catch (IOException ex) {
                return NOT_OPEN;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "Interrupted before the editor answered.";
            }
        }
    }
}
