import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import io.github.llm4j.loom.observe.Observe;
import io.github.llm4j.loom.observe.ObserveOptions;
import io.github.llm4j.loom.observe.ObserveProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gate G7 helper (run with the Loom CLI jar on the class path; see scripts/spring-starter-g7.sh).
 *   serve PORT COUNTFILE        a stand-in Ollama server: every /api/chat is counted in COUNTFILE and answered
 *   run STORE SCRIPT MODEL_URL  starts "Main" in a file-persisted host; prints SUSPENDED/COMPLETED, then (if suspended) waits to be killed
 *   observe STORE SCRIPT MODEL_URL PORT   opens the real loom-inspect page, starts a run on a slow stand-in, and prints what GET /api/runs lists while it is running
 *   answer STORE SCRIPT MODEL_URL RUNID STEPID TEXT   a new host on the same store: records the answer and waits for the run to finish
 */
public class Host7 {
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "serve" -> serve(Integer.parseInt(a[1]), Path.of(a[2]), a.length > 3 ? Long.parseLong(a[3]) : 0);
            case "observe" -> {
                ObserveProvider.Session session = Observe.open(ObserveOptions.fromCommandLine(a[4], "127.0.0.1", "metadata", "g7", null, null, null, false, null, null, null, null, System::getenv), System.out);
                LoomScript parsed = new LoomLoader().load(a[2]);
                LoomHost host = LoomHost.builder(parsed, new ToolRegistry(), new DefaultLLMClientFactory(Map.of("OLLAMA_BASE_URL", a[3])::get, null))
                        .persistence(Persistence.file(Path.of(a[1]))).scriptRef(Path.of(a[2]).toAbsolutePath().toString()).baseDir(Path.of(a[2]).toAbsolutePath().getParent()).observe(session).build();
                host.start(RunRequest.of("Main", Map.of()).withRunId("g7-observed"));
                HttpClient http = HttpClient.newHttpClient();
                String body = "";
                for (int i = 0; i < 100 && !body.contains("Main"); i++) {
                    Thread.sleep(200);
                    try {
                        body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a[4] + "/api/runs")).build(), HttpResponse.BodyHandlers.ofString()).body();
                    } catch (Exception e) {
                        body = "";
                    }
                }
                System.out.println("RUNS " + body);
                System.exit(body.contains("Main") ? 0 : 1);
            }
            case "run" -> {
                LoomHost host = host(a[1], a[2], a[3]);
                RunOutcome out = host.run(RunRequest.of("Main", Map.of()).withRunId("g7-run"));
                if (out instanceof RunOutcome.Suspended s) {
                    System.out.println("SUSPENDED " + s.runId() + " " + s.stepId());
                    System.out.flush();
                    Thread.sleep(Long.MAX_VALUE);
                }
                System.out.println(out instanceof RunOutcome.Completed ? "COMPLETED" : "OTHER " + out);
            }
            case "answer" -> {
                LoomHost host = host(a[1], a[2], a[3]);
                var result = host.answer(a[4], a[5], a[6], "g7");
                System.out.println("ANSWER recorded=" + result.recorded() + " " + result.message());
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline) {
                    var rec = host.run(a[4]);
                    if (rec.isPresent() && rec.get().status().name().equals("COMPLETED")) { System.out.println("COMPLETED"); host.close(); return; }
                    Thread.sleep(100);
                }
                System.out.println("TIMEOUT " + host.run(a[4]));
                System.exit(1);
            }
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static LoomHost host(String store, String script, String modelUrl) throws Exception {
        LoomScript parsed = new LoomLoader().load(script);
        return LoomHost.builder(parsed, new ToolRegistry(), new DefaultLLMClientFactory(Map.of("OLLAMA_BASE_URL", modelUrl)::get, null))
                .persistence(Persistence.file(Path.of(store))).scriptRef(Path.of(script).toAbsolutePath().toString()).baseDir(Path.of(script).toAbsolutePath().getParent()).build();
    }

    static void serve(int port, Path countFile, long delay) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException ignored) { }
            int n = calls.incrementAndGet();
            try { Files.writeString(countFile, Integer.toString(n)); } catch (Exception ignored) { }
            String body = "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"```json\\n{\\\"thought\\\": \\\"t\\\", \\\"final_answer\\\": \\\"reply " + n + "\\\"}\\n```\"},\"done\":true,\"prompt_eval_count\":10,\"eval_count\":5}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        System.out.println("SERVING " + port);
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
