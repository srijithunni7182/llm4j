package web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.loom.eval.MockModels;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LLMClientFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The web host: a page at http://localhost:8080, and three calls behind it. It runs main.loom and shows what the script does; it decides nothing.
 *   sh run.sh --mock    models that cost nothing (the article is "[mock answer]"): to see the page and the approval work
 *   sh run.sh           the real model named in main.loom; needs the key in .env, and spends money
 */
public final class App {

    private static final ObjectMapper JSON = new ObjectMapper();

    private App() {}

    public static void main(String[] args) throws Exception {
        boolean mock = args.length > 0 && args[0].equals("--mock");
        Path script = Path.of("src", "main", "resources", "main.loom");
        LLMClientFactory models;
        if (mock) {
            models = new MockModels();
            System.out.println("Mock mode: no model is called, nothing is spent.");
        } else {
            Map<String, String> env = new HashMap<>(System.getenv());
            loadDotEnv(env);
            models = new DefaultLLMClientFactory(env::get);
            System.out.println("REAL mode: every article costs money (the script's budget stops a run at 60000 tokens). Use --mock to try it for free.");
        }
        int port = Integer.getInteger("web.port", 8080);
        HttpServer server = start(new Session(script, models), port);
        System.out.println("Open http://localhost:" + server.getAddress().getPort());
    }

    /** Starts the server on {@code port} (0 picks a free one). */
    public static HttpServer start(Session session, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> page(ex));
        server.createContext("/api/state", ex -> send(ex, 200, "application/json", JSON.writeValueAsBytes(session.state())));
        server.createContext("/api/run", ex -> {
            String topic = field(ex, "topic");
            if (topic.isBlank()) send(ex, 400, "text/plain", "Type a topic first.".getBytes(StandardCharsets.UTF_8));
            else send(ex, session.start(topic) ? 200 : 409, "text/plain", new byte[0]);
        });
        server.createContext("/api/answer", ex -> send(ex, session.answer(field(ex, "answer")) ? 200 : 409, "text/plain", new byte[0]));
        server.start();
        return server;
    }

    private static String field(HttpExchange ex, String name) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) return "";
        var node = JSON.readTree(ex.getRequestBody());
        return node.has(name) ? node.get(name).asText() : "";
    }

    private static void page(HttpExchange ex) throws IOException {
        try (InputStream in = App.class.getResourceAsStream("/web/index.html")) {
            send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
        ex.close();
    }

    /** NAME=value lines of .env, for a run on your own machine. A variable already set in the shell wins. The values are never printed. */
    static void loadDotEnv(Map<String, String> env) throws IOException {
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) return;
        for (String line : Files.readAllLines(file)) {
            String l = line.strip();
            int eq = l.indexOf('=');
            if (l.isEmpty() || l.startsWith("#") || eq < 1) continue;
            String value = l.substring(eq + 1).strip();
            if (!value.isEmpty()) env.putIfAbsent(l.substring(0, eq).strip(), value);
        }
    }
}
