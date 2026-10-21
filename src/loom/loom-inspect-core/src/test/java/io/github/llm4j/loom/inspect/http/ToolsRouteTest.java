package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R2: the tools and tasks of every run, rolled up, worst first, over a read-only route. */
class ToolsRouteTest {

    private static List<String> names(JsonNode rows) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get("kind").asText() + ":" + r.get("name").asText()));
        return out;
    }

    @Test
    void anEmptyServerAnswersWithNoRowsAndZeroCounts() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            JsonNode a = f.json("/api/tools");
            assertThat(a.get("rows")).isEmpty();
            assertThat(a.get("counts").get("failing").asInt()).isZero();
            assertThat(a.get("counts").get("healthy").asInt()).isZero();
        }
    }

    @Test
    void everyRunsToolsAreRolledUpAndFailingOnesComeFirst() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            for (int i = 0; i < 6; i++)
                f.tool("shop", "r1", "Editor", "Wiki", "private", null, i + 1);
            for (int i = 0; i < 4; i++)
                f.tool("shop", "r2", "Writer", "Search", "untrusted", "timeout", 10 + i);
            for (int i = 0; i < 2; i++)
                f.tool("blog", "r3", "Editor", "Search", "untrusted", "timeout", 20 + i);
            JsonNode a = f.json("/api/tools");
            assertThat(names(a.get("rows"))).containsExactly("tool:Search", "tool:Wiki");
            JsonNode search = a.get("rows").get(0);
            assertThat(search.get("calls").asInt()).isEqualTo(6);
            assertThat(search.get("health").get("state").asText()).isEqualTo("failing");
            assertThat(search.get("health").get("failures").get("timeout").asInt()).isEqualTo(6);
            assertThat(search.get("p95Ms").asLong()).isPositive();
            assertThat(search.get("histogram").isArray()).isTrue();
            assertThat(a.get("counts").get("failing").asInt()).isEqualTo(1);
            assertThat(a.get("counts").get("healthy").asInt()).isEqualTo(1);
        }
    }

    @Test
    void eachRowNamesTheAgentsThatUseItAndTheRunItLastFailedIn() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.tool("shop", "r1", "Writer", "Search", "untrusted", "timeout", 5);
            f.tool("shop", "r2", "Editor", "Search", "untrusted", "timeout", 50);
            f.tool("shop", "r1", "Writer", "Wiki", "private", null, 6);
            JsonNode a = f.json("/api/tools");
            JsonNode search = a.get("rows").get(0);
            List<String> agents = new ArrayList<>();
            search.get("agents").forEach(x -> agents.add(x.asText()));
            assertThat(agents).containsExactlyInAnyOrder("Editor", "Writer");
            assertThat(search.get("lastRun").get("client").asText()).isEqualTo("shop");
            assertThat(search.get("lastRun").get("traceId").asText())
                    .isEqualTo(FleetFixtures.trace("r2"));
            assertThat(search.get("lastRun").get("category").asText()).isEqualTo("timeout");
            JsonNode wiki = a.get("rows").get(1);
            assertThat(wiki.has("lastRun"))
                    .as("a tool that never failed has no last failure")
                    .isFalse();
        }
    }

    @Test
    void aTaskAndAToolOfOneNameAreTwoRows() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.tool("shop", "r1", "A", "Search", "untrusted", null, 1);
            f.task("shop", "r1", "Search", "server_error", 2);
            assertThat(names(f.json("/api/tools").get("rows")))
                    .containsExactlyInAnyOrder("tool:Search", "task:Search");
        }
    }

    @Test
    void aClientCanBeAskedFor() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.tool("shop", "r1", "A", "Search", "untrusted", "timeout", 1);
            f.tool("blog", "r2", "B", "Wiki", "private", null, 2);
            assertThat(names(f.json("/api/tools?client=shop").get("rows")))
                    .containsExactly("tool:Search");
            assertThat(names(f.json("/api/tools?client=blog").get("rows")))
                    .containsExactly("tool:Wiki");
            assertThat(f.json("/api/tools?client=nobody").get("rows")).isEmpty();
            assertThat(f.json("/api/tools?client=" + "x".repeat(500)).get("rows")).isEmpty();
        }
    }

    @Test
    void itIsReadOnly() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            HttpResponse<String> post =
                    f.http.send(
                            HttpRequest.newBuilder(URI.create(f.base + "/api/tools"))
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isEqualTo(405);
        }
    }

    @Test
    void aGateInFrontOfTheApiAlsoGuardsIt() throws Exception {
        InspectServer.Config config =
                new InspectServer.Config()
                        .gate(
                                x -> {
                                    x.sendResponseHeaders(401, -1);
                                    return false;
                                });
        try (FleetFixtures f = new FleetFixtures().start(config)) {
            assertThat(f.get("/api/tools").statusCode()).isEqualTo(401);
            assertThat(f.get("/api/security").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void aTableOfFiveHundredToolsAnswersAtOnce() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            for (int i = 0; i < 500; i++)
                f.tool(
                        "shop",
                        "r1",
                        "A",
                        "Tool" + i,
                        "private",
                        i % 3 == 0 ? "timeout" : null,
                        i * 0.01);
            long start = System.nanoTime();
            JsonNode a = f.json("/api/tools");
            assertThat(a.get("rows")).hasSize(500);
            assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(3_000L);
        }
    }

    @Test
    void theRunListCarriesTheFailingCountForTheTabBadge() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            assertThat(f.json("/api/runs").get("fleet").get("toolsFailing").asInt()).isZero();
            for (int i = 0; i < 6; i++)
                f.tool("shop", "r1", "Editor", "Wiki", "private", null, i + 1);
            for (int i = 0; i < 4; i++)
                f.tool("shop", "r2", "Writer", "Search", "untrusted", "timeout", 10 + i);
            assertThat(f.json("/api/runs").get("fleet").get("toolsFailing").asInt()).isEqualTo(1);
            assertThat(f.json("/api/runs").get("runs")).hasSize(2);
        }
    }

    @Test
    void eachRowCarriesTheLastTenOutcomesAcrossRunsAndTheLatencyBounds() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            for (int i = 0; i < 6; i++)
                f.tool("shop", "r1", "Editor", "Wiki", "private", null, i + 1);
            for (int i = 0; i < 4; i++)
                f.tool("shop", "r2", "Writer", "Search", "untrusted", "timeout", 10 + i);
            for (int i = 0; i < 9; i++)
                f.tool("blog", "r3", "Editor", "Search", "untrusted", null, 30 + i);
            JsonNode a = f.json("/api/tools");
            JsonNode search = null;
            JsonNode wiki = null;
            for (JsonNode r : a.get("rows")) {
                if (r.get("name").asText().equals("Search")) search = r;
                if (r.get("name").asText().equals("Wiki")) wiki = r;
            }
            assertThat(search.get("recent")).hasSize(10);
            assertThat(wiki.get("recent")).hasSize(6);
            wiki.get("recent").forEach(o -> assertThat(o.asText()).isEmpty());
            assertThat(search.get("histogramBounds").size())
                    .isEqualTo(search.get("histogram").size() - 1);
        }
    }
}
