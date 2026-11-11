import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.a2a.*;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Scratch: llm4j's handler over HTTP on loopback with an exposed Loom workflow, for the third-party compatibility kit. */
public class TckServer {
    static final ObjectMapper JSON = new ObjectMapper();

    /** The exposed workflow the compatibility kit talks to: it echoes the text it is sent. */
    static final String SCRIPT = """
            agent_channel CallerDesk {
                audience: caller
                max_questions: 2
            }

            budget { tokens: 1000 calls: 5 }

            expose Echo {
                workflow: Echo
                description: "Echoes what it is sent."
                agent_channel: CallerDesk
                text_input: text
                max_input: 64k
            }

            workflow Echo(text) {
                return "echo: {text}"
            }
            """;

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        Path work = java.nio.file.Files.createTempDirectory("a2a-tck-server");
        Path script = work.resolve("echo.loom");
        java.nio.file.Files.writeString(script, SCRIPT);
        Path store = work.resolve("store");
        var loaded = new LoomLoader().load(script.toString());
        Map<String, TaskRunner.OwnedTask> tasks = new ConcurrentHashMap<>();
        TaskStore taskStore = new TaskStore() {
            public void save(TaskRunner.OwnedTask t) { tasks.put(t.task().id(), t); }
            public Optional<TaskRunner.OwnedTask> find(String scope, String id) { return Optional.ofNullable(tasks.get(id)).filter(t -> t.scope().equals(scope)); }
            public List<TaskRunner.OwnedTask> list(String scope) { return tasks.values().stream().filter(t -> t.scope().equals(scope)).toList(); }
        };
        var runner = new LoomTaskRunner(loaded.getExposes().get(0), loaded, taskStore, Clock.systemUTC());
        var host = LoomHost.builder(loaded, new ToolRegistry(), model -> null)
                .persistence(Persistence.file(store))
                .scriptRef(script.toAbsolutePath().toString())
                .baseDir(script.toAbsolutePath().getParent())
                .customizer(runner.wiring())
                .build();
        runner.attach(host);
        var handler = new A2AHandler(scope -> runner);
        String url = "http://127.0.0.1:" + port + "/a2a";
        var card = AgentCardBuilder.of("Echo", "Echoes what it is sent.")
                .skill(new AgentCardView.Skill("Echo", "Echo", "Echoes", List.of(), List.of(), List.of("text/plain"), List.of("text/plain")))
                .acceptsMediaType("text/plain").producesMediaType("text/plain")
                .endpoint(new AgentCardView.Endpoint(url, "JSONRPC", "1.0", null)).build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/.well-known/agent-card.json", ex -> {
            byte[] body = card.json().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("ETag", card.etag());
            ex.getResponseHeaders().add("Cache-Control", "max-age=300");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/a2a", ex -> rpc(handler, ex));
        server.start();
        System.out.println("listening on " + url);
        Thread.currentThread().join();
    }

    static void rpc(A2AHandler handler, HttpExchange ex) throws java.io.IOException {
        String request = new String(ex.getRequestBody().readNBytes(1_000_000), StandardCharsets.UTF_8);
        JsonNode id = A2AJsonRpc10.idOf(request);
        String response;
        try {
            A2AJsonRpc10.requireSupportedVersion(ex.getRequestHeaders().getFirst("A2A-Version"));
            var call = A2AJsonRpc10.parseRequest(request);
            var answer = handler.handle(new CallerIdentity("tck"), call.request()).toCompletableFuture().get(30, TimeUnit.SECONDS);
            response = A2AJsonRpc10.writeResponse(id, call.request(), answer);
        } catch (A2AException e) {
            response = A2AJsonRpc10.writeError(id, e);
        } catch (ExecutionException e) {
            response = A2AJsonRpc10.writeError(id, e.getCause() instanceof A2AException x ? x : new A2AException("internal error"));
        } catch (Exception e) {
            response = A2AJsonRpc10.writeError(id, new A2AException("internal error"));
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }
}
