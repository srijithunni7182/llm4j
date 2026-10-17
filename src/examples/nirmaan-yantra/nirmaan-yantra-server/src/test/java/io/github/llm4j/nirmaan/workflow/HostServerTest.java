package io.github.llm4j.nirmaan.workflow;

import static io.github.llm4j.nirmaan.workflow.Factory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.eval.testing.ScriptedClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.nirmaan.host.FactoryConfig;
import io.github.llm4j.nirmaan.host.FactoryHost;
import io.github.llm4j.nirmaan.host.NirmaanServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The web host over real HTTP: the real workflow and tasks, a scripted model, a temporary workspace. */
class HostServerTest {

    private static final String E2E_COMMAND = "python3 e2e-tests/run_e2e.py";
    private final String workspaceBefore = System.getProperty("nirmaan.workspace");
    private final HttpClient http = HttpClient.newHttpClient();
    private NirmaanServer server;
    private FactoryHost host;

    @TempDir Path temp;

    @AfterEach
    void stop() {
        if (server != null) server.close();
        if (host != null) host.close();
        if (workspaceBefore != null) System.setProperty("nirmaan.workspace", workspaceBefore);
    }

    /** Starts a host whose model is the scripted one. */
    private void up(Supplier<ScriptedClient> model) throws Exception {
        System.setProperty("nirmaan.workspace", temp.resolve("ws").toString());
        Path ui = Files.createDirectories(temp.resolve("ui"));
        Files.writeString(ui.resolve("index.html"), "<!doctype html><title>ui</title>");
        Path project = Path.of("").toAbsolutePath();
        FactoryConfig config = new FactoryConfig(Factory.SCRIPT, project.resolve("prices.properties"), temp.resolve("none.store"), temp.resolve("none.key"), ui, "127.0.0.1", 0, "", "nirmaan");
        ScriptedClient client = model.get();
        FactoryHost.Models models = new FactoryHost.Models() {
            @Override public LLMClientFactory factory(io.github.llm4j.secret.SecretStore secrets) { return name -> client; }

            @Override public Function<String, String> envLookup() { return name -> "test"; }
        };
        host = new FactoryHost(config, models);
        server = new NirmaanServer(host, ui, "127.0.0.1", 0);
        server.start();
    }

    private record Sse(String event, String data) {}

    /** Reads the event stream of a project on a background thread. */
    private List<Sse> listen(String id) {
        List<Sse> events = new CopyOnWriteArrayList<>();
        Thread t = new Thread(() -> {
            try {
                HttpResponse<java.util.stream.Stream<String>> response = http.send(
                        HttpRequest.newBuilder(URI.create(server.url() + "/api/project/" + id + "/stream")).build(), HttpResponse.BodyHandlers.ofLines());
                String event = null;
                StringBuilder data = new StringBuilder();
                for (String line : (Iterable<String>) response.body()::iterator) {
                    if (line.startsWith("event: ")) event = line.substring(7);
                    else if (line.startsWith("data: ")) data.append(data.length() > 0 ? "\n" : "").append(line.substring(6));
                    else if (line.isEmpty() && event != null) {
                        events.add(new Sse(event, data.toString()));
                        event = null;
                        data.setLength(0);
                    }
                }
            } catch (Exception ignored) {
                // the server was stopped
            }
        }, "sse-listener");
        t.setDaemon(true);
        t.start();
        return events;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    private static boolean has(List<Sse> events, String name, String contains) {
        return events.stream().anyMatch(e -> e.event().equals(name) && e.data().contains(contains));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(HttpResponse<String> started) throws Exception {
        return JSON.readTree(started.body()).get("projectId").asText();
    }

    @Test
    void aRunStreamsWhatTheCrewDoesAndLeavesTheProjectToBrowseAndDownload() throws Exception {
        up(() -> smooth(E2E_COMMAND).build());

        HttpResponse<String> started = post("/api/project/start", "a function that adds two numbers");
        String id = idOf(started);
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "status", "\"done\""), "the run to finish");

        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(events.stream().filter(e -> e.event().equals("log")).map(Sse::data).toList())
                .anyMatch(l -> l.startsWith("Aditi: Writing the product requirements"))
                .anyMatch(l -> l.startsWith("Rishi: Chose Python 3 with unittest"))
                .anyMatch(l -> l.startsWith("Vihaan: Wrote 2 files: test_add.py, add.py"))
                .anyMatch(l -> l.startsWith("Dhruv: Running: python3 -m unittest"))
                .anyMatch(l -> l.startsWith("Vishnu: Verdict: APPROVED"))
                .anyMatch(l -> l.equals("Project Ready."));
        assertThat(events.stream().filter(e -> e.event().equals("spend")).reduce((a, b) -> b).orElseThrow().data()).contains("\"calls\":7");
        assertThat(JSON.readTree(get("/api/project/" + id + "/state").body()).get("status").asText()).isEqualTo("done");
        assertThat(get("/api/project/" + id + "/artifacts").body()).contains("add.py", "SPEC.md", "APPROVAL.md");
        assertThat(get("/api/project/" + id + "/artifacts/add.py").body()).isEqualTo(ADD_RIGHT);
        HttpResponse<byte[]> zip = http.send(HttpRequest.newBuilder(URI.create(server.url() + "/api/project/" + id + "/download")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(zip.statusCode()).isEqualTo(200);
        assertThat(zip.body()[0]).isEqualTo((byte) 'P');
        assertThat(zip.headers().firstValue("Content-Type")).contains("application/zip");
        // a late visitor sees the whole story, not just what happens from now on
        List<Sse> late = listen(id);
        waitFor(() -> has(late, "log", "Project Ready."), "the replay");
        assertThat(late.stream().filter(e -> e.event().equals("log")).count()).isEqualTo(events.stream().filter(e -> e.event().equals("log")).count());
    }

    @Test
    void aQuestionReachesTheScreenAndOnlyAChoiceIsAccepted() throws Exception {
        String odd = "python3 -c \"open('ran.txt', 'w').write('x'); import sys; sys.exit(0)\"";
        up(() -> smooth(odd).build());

        String id = idOf(post("/api/project/start", "{\"idea\": \"add two numbers\"}".replace("{", "{").replace("}", "}")));
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "question", "RunCommand"), "the approval question");
        Sse question = events.stream().filter(e -> e.event().equals("question")).findFirst().orElseThrow();

        assertThat(question.data()).contains("\"kind\":\"approval\"").contains("\"choices\":[\"yes\",\"no\"]");
        assertThat(JSON.readTree(get("/api/project/" + id + "/state").body()).get("status").asText()).isEqualTo("waiting");
        assertThat(post("/api/project/" + id + "/answer", "maybe").statusCode()).as("not one of the choices").isEqualTo(400);
        assertThat(post("/api/project/" + id + "/answer", "yes").statusCode()).isEqualTo(200);
        waitFor(() -> has(events, "status", "\"done\""), "the run to finish");
        assertThat(has(events, "log", "You: yes")).isTrue();
        assertThat(post("/api/project/" + id + "/answer", "yes").statusCode()).as("nothing is being asked now").isEqualTo(409);
        assertThat(temp.resolve("ws").resolve(id).resolve("code").resolve("ran.txt")).exists();
    }

    @Test
    void onlyOneRunAtATimeAndAStopEndsIt() throws Exception {
        String odd = "python3 -c \"import sys; sys.exit(0)\"";
        up(() -> smooth(odd).build());

        String id = idOf(post("/api/project/start", "first idea"));
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "question", "RunCommand"), "the run to wait on a question");

        HttpResponse<String> second = post("/api/project/start", "second idea");
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("one at a time");

        assertThat(post("/api/project/" + id + "/stop", "").statusCode()).isEqualTo(200);
        waitFor(() -> has(events, "status", "\"stopped\"") || has(events, "status", "\"failed\""), "the run to end");
        assertThat(has(events, "status", "\"done\"")).isFalse();
        String[] next = new String[1];
        waitFor(() -> {
            try {
                HttpResponse<String> r = post("/api/project/start", "after the stop");
                if (r.statusCode() == 200) next[0] = idOf(r);
                return r.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        }, "the next run to be allowed");
        // leave nothing running in the temporary folder
        List<Sse> nextEvents = listen(next[0]);
        waitFor(() -> has(nextEvents, "question", "RunCommand"), "the second run to ask its question");
        post("/api/project/" + next[0] + "/stop", "");
        waitFor(() -> has(nextEvents, "status", "\"stopped\"") || has(nextEvents, "status", "\"failed\""), "the second run to end");
    }

    @Test
    void anAnswerCutOffAtTheOutputLimitIsFlaggedToThePerson() {
        io.github.llm4j.loom.execution.TraceEvent cut = new io.github.llm4j.loom.execution.TraceEvent("model_call", "Rishi", "Main/s2", "x",
                java.util.Map.of("truncated", true, "finish_reason", "MAX_TOKENS"), java.time.Instant.now());
        var shown = io.github.llm4j.nirmaan.host.UiEventsAccess.convert(cut);

        assertThat(shown).anyMatch(l -> l.contains("Rishi's answer was cut off at the output limit"));
    }

    @Test
    void theHostRefusesWhatAnotherWebsiteOrAnotherAddressCouldTry() throws Exception {
        up(() -> smooth(E2E_COMMAND).build());
        String base = server.url();

        // another site's page posting to localhost: its Origin is not ours
        HttpResponse<String> crossSite = http.send(HttpRequest.newBuilder(URI.create(base + "/api/project/start")).header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("spend your money")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(crossSite.statusCode()).isEqualTo(403);

        // DNS rebinding: the Host header names something else (raw socket: the HTTP client will not set Host)
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write("GET /api/project/x/state HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();
            assertThat(status).contains("403");
        }

        assertThat(post("/api/project/start", "x".repeat(4001)).statusCode()).as("an idea has a limit").isEqualTo(400);
        assertThat(post("/api/project/start", "   ").statusCode()).isEqualTo(400);
        assertThat(get("/api/project/nope/state").statusCode()).isEqualTo(404);
        assertThat(get("/api/project/..%2F..%2Fetc/artifacts").statusCode()).isEqualTo(404);
        assertThat(get("/%2e%2e/%2e%2e/etc/passwd").statusCode()).as("the UI folder cannot be escaped").isNotEqualTo(200);
        assertThat(get("/").body()).contains("<title>ui</title>");
        assertThat(get("/some/page").body()).as("a page of the single-page app").contains("<title>ui</title>");
        assertThat(get("/missing.js").statusCode()).isEqualTo(404);

        String id = idOf(post("/api/project/start", "an idea"));
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "status", "\"done\""), "the run to finish");
        assertThat(get("/api/project/" + id + "/artifacts/..%2F..%2Fmeta%2Fspec.json").statusCode()).as("the factory's own folder is not served").isEqualTo(404);
        assertThat(get("/api/project/" + id + "/artifacts/%2Fetc%2Fpasswd").statusCode()).isEqualTo(404);
        HttpResponse<String> file = get("/api/project/" + id + "/artifacts/add.py");
        assertThat(file.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(file.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void thereIsNoSignInSoItWillNotListenBeyondThisComputer() throws Exception {
        up(() -> smooth(E2E_COMMAND).build());

        assertThatThrownBy(() -> new NirmaanServer(host, temp, "0.0.0.0", 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
    }

    @Test
    void anAnswerInTheWrongFormatIsCorrectedByLoomAndTheRunCarriesOn() throws Exception {
        // what Gemini really did once: Drishti answered in YAML ("files:" with block scalars) instead of JSON. Loom now rejects that,
        // tells her what was wrong, and the second answer is used: the run neither stops nor needs a person.
        String yaml = answer("notes: covers the game\nfiles:\n  - path: e2e-tests/run_e2e.py\n    content: |\n      exit 0\n");
        up(() -> smooth(E2E_COMMAND).whenThen("MODE: WRITE", yaml, drishti(file("e2e-tests/run_e2e.py", E2E))).build());

        String id = idOf(post("/api/project/start", "a snake game"));
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "status", "\"done\""), "the run to finish");

        assertThat(get("/api/project/" + id + "/artifacts/e2e-tests/run_e2e.py").body()).isEqualTo(E2E);
    }

    @Test
    void anAgentThatStaysInTheWrongFormatStopsTheRunSafelyAndTheScreenSaysWhy() throws Exception {
        String yaml = answer("notes: covers the game\nfiles:\n  - path: e2e-tests/run.sh\n    content: |\n      exit 0\n");
        up(() -> smooth(E2E_COMMAND).when("MODE: WRITE", yaml).build());

        String id = idOf(post("/api/project/start", "a snake game"));
        List<Sse> events = listen(id);
        waitFor(() -> has(events, "status", "\"failed\""), "the run to stop");

        assertThat(has(events, "log", "did not match the schema")).isTrue();
        assertThat(JSON.readTree(get("/api/project/" + id + "/state").body()).get("error").asText()).contains("not JSON");
        assertThat(get("/api/project/" + id + "/artifacts").body()).as("what was built before the stop is still there").contains("PRD.md", "SPEC.md");
    }
}
