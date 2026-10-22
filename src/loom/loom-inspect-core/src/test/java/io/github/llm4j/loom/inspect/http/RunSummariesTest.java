package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** R6: a run's summary says how many security incidents are open, so a tab can show it. */
class RunSummariesTest {

    @Test
    void aRunWithNoSecurityIncidentsSaysZero() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.runStart("shop", "r1");
            f.tool("shop", "r1", "A", "Calc", "", null, 1);
            assertThat(f.json("/api/runs").get("runs").get(0).get("security").asInt()).isZero();
        }
    }

    @Test
    void openTrifectaInjectionAndAccessIncidentsAreCounted() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.runStart("shop", "r1");
            f.tool("shop", "r1", "A", "Swiss", "outward,private,untrusted", null, 1);
            f.tool("shop", "r1", "B", "Nope", null, "unknown_tool", 2);
            JsonNode run = f.json("/api/runs").get("runs").get(0);
            assertThat(run.get("security").asInt()).isEqualTo(2);
            assertThat(run.get("incidents").asInt()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void theOldFieldsAreStillThere() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.runStart("shop", "r1");
            JsonNode run = f.json("/api/runs").get("runs").get(0);
            assertThat(run.fieldNames())
                    .toIterable()
                    .contains(
                            "client",
                            "traceId",
                            "runId",
                            "workflow",
                            "status",
                            "totals",
                            "runningAgents",
                            "incidents");
        }
    }
}
