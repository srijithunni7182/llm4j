package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** R29, R30: a run's marks and its view as it was, over read-only routes behind the same gate as the rest of the API. */
class TimelineRouteTest {

    private static void run(FleetFixtures f) {
        f.runStart("shop", "r1");
        for (int i = 0; i < 6; i++) f.tool("shop", "r1", "Writer", "Search", "untrusted", "timeout", 10 + i);
    }

    @Test
    void theTimelineListsTheMarksWithTheirPointsAndTheRangeOfTheHistory() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            run(f);
            JsonNode t = f.json("/api/runs/" + FleetFixtures.trace("r1") + "/timeline");
            assertThat(t.get("marks").size()).isPositive().isLessThanOrEqualTo(300);
            assertThat(t.get("marks").get(0).get("kind").asText()).isEqualTo("start");
            assertThat(t.get("marks").findValuesAsText("kind")).contains("incident-open");
            assertThat(t.get("history").get("firstSeq").asLong()).isZero();
            assertThat(t.get("history").get("lastSeq").asLong()).isEqualTo(6);
        }
    }

    @Test
    void aPointInTheRunAnswersWithTheViewAsItWasAndSaysItIsNotLive() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            run(f);
            String trace = FleetFixtures.trace("r1");
            JsonNode live = f.json("/api/runs/" + trace);
            JsonNode early = f.json("/api/runs/" + trace + "?at=2");
            assertThat(live.get("live").asBoolean()).isTrue();
            assertThat(early.get("live").asBoolean()).isFalse();
            assertThat(early.get("seq").asLong()).isEqualTo(2);
            assertThat(early.get("asOf").asText()).isNotEmpty();
            assertThat(early.get("totals").get("calls").asLong()).isLessThanOrEqualTo(live.get("totals").get("calls").asLong());
            assertThat(early.get("incidents")).isEmpty();
            assertThat(live.get("incidents")).isNotEmpty();
            assertThat(early.has("controls")).as("a snapshot offers no controls").isFalse();
            assertThat(f.json("/api/runs/" + trace + "?at=6").get("incidents")).isNotEmpty();
        }
    }

    @Test
    void aPointOutsideTheHistoryIsRefusedWithWhereTheHistoryIs() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            run(f);
            String trace = FleetFixtures.trace("r1");
            HttpResponse<String> late = f.get("/api/runs/" + trace + "?at=99");
            assertThat(late.statusCode()).isEqualTo(404);
            assertThat(FleetFixtures.JSON.readTree(late.body()).get("historyStartsAt").asText()).isNotEmpty();
            assertThat(f.get("/api/runs/" + trace + "?at=-3").statusCode()).isEqualTo(404);
            assertThat(f.get("/api/runs/" + trace + "?at=soon").statusCode()).isEqualTo(400);
            assertThat(f.get("/api/runs/" + "0".repeat(32) + "/timeline").statusCode()).isEqualTo(404);
            assertThat(f.get("/api/runs/" + "0".repeat(32) + "?at=1").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void theRoutesAreReadOnlyAndGatedLikeTheOthers() throws Exception {
        InspectServer.Gate deny = x -> {
            InspectServer.send(x, 401, "application/json", "{\"error\":\"login required\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return false;
        };
        try (FleetFixtures f = new FleetFixtures().start(InspectServer.config().gate(deny))) {
            String trace = FleetFixtures.trace("r1");
            assertThat(f.get("/api/runs/" + trace + "/timeline").statusCode()).isEqualTo(401);
            assertThat(f.get("/api/runs/" + trace + "?at=1").statusCode()).isEqualTo(401);
        }
        try (FleetFixtures f = new FleetFixtures().start()) {
            run(f);
            HttpResponse<String> post = f.http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(f.base + "/api/runs/" + FleetFixtures.trace("r1") + "/timeline"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(post.statusCode()).isIn(404, 405);
        }
    }
}
