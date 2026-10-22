package io.github.llm4j.loom.inspect.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.inspect.otel.Ids;
import io.github.llm4j.loom.inspect.otel.LogRec;
import io.github.llm4j.loom.inspect.otel.OtlpJson;
import io.github.llm4j.loom.inspect.otel.Resource;
import io.github.llm4j.loom.inspect.otel.Semconv;
import io.github.llm4j.loom.inspect.otel.SpanRec;
import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.state.RunRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A registry with runs in it, a real loopback server over it, and a way to ask it: for the fleet
 * routes' tests.
 */
final class FleetFixtures implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    static final long T0 = OtlpJson.nanos(Instant.parse("2026-10-14T10:00:00Z"));

    final RunRegistry registry = new RunRegistry();
    final HttpClient http = HttpClient.newHttpClient();
    InspectServer server;
    String base;
    private long n;

    FleetFixtures start() {
        server = new InspectServer(registry, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        return this;
    }

    FleetFixtures start(InspectServer.Config config) {
        server = new InspectServer(registry, config.bind("127.0.0.1", 0));
        server.start();
        base = "http://127.0.0.1:" + server.port();
        return this;
    }

    @Override
    public void close() {
        if (server != null) server.stop();
    }

    static String trace(String runId) {
        return Ids.traceId(runId);
    }

    void runStart(String client, String runId) {
        registry.applyLogs(
                Resource.of(client, "i_1"),
                List.of(
                        new LogRec(
                                T0 + (++n) * 1_000L,
                                LogRec.INFO,
                                "start",
                                Map.of(
                                        Semconv.EVENT_NAME,
                                        Semconv.EV_RUN_START,
                                        Semconv.LOOM_SEQ,
                                        n,
                                        Semconv.LOOM_RUN_ID,
                                        runId,
                                        Semconv.LOOM_WORKFLOW,
                                        "Main"),
                                trace(runId),
                                null)));
    }

    /** A tool span that ran and worked, or failed with a category. */
    void tool(
            String client,
            String runId,
            String agent,
            String tool,
            String reach,
            String category,
            double sec) {
        span(client, runId, Semconv.KIND_TOOL, agent, tool, reach, category, sec);
    }

    /** A task span: ok or failed with a category. */
    void task(String client, String runId, String task, String category, double sec) {
        span(client, runId, Semconv.KIND_TASK, null, task, null, category, sec);
    }

    private void span(
            String client,
            String runId,
            String kind,
            String agent,
            String name,
            String reach,
            String category,
            double sec) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put(Semconv.LOOM_SPAN_KIND, kind);
        if (agent != null) a.put(Semconv.GEN_AI_AGENT_NAME, agent);
        if (Semconv.KIND_TOOL.equals(kind)) a.put(Semconv.GEN_AI_TOOL_NAME, name);
        else a.put(Semconv.LOOM_TASK, name);
        a.put(Semconv.LOOM_STEP, "Main/s0");
        if (reach != null) {
            a.put(Semconv.LOOM_TOOL_REACH, reach);
            a.put(Semconv.LOOM_TOOL_REACH_KNOWN, true);
        }
        if (category != null) a.put(Semconv.ERROR_TYPE, category);
        long end = T0 + (long) (sec * 1_000_000_000L);
        registry.applySpans(
                Resource.of(client, "i_1"),
                List.of(
                        new SpanRec(
                                trace(runId),
                                Ids.spanId(runId, "s" + (++n)),
                                null,
                                name,
                                SpanRec.CLIENT,
                                end - 100_000_000L,
                                end,
                                a,
                                category == null ? SpanRec.STATUS_OK : SpanRec.STATUS_ERROR,
                                null,
                                List.of())));
    }

    void accept(String client, String runId, String agent, String reason) {
        registry.setRules(
                client,
                trace(runId),
                Map.of(
                        agent,
                        new InspectRules(
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                new InspectRules.Security(null, null, null, reason))));
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(String path) throws Exception {
        HttpResponse<String> r = get(path);
        if (r.statusCode() != 200)
            throw new IllegalStateException(path + " answered " + r.statusCode() + ": " + r.body());
        return JSON.readTree(r.body());
    }
}
