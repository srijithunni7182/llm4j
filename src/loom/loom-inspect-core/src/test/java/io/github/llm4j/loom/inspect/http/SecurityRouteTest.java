package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R3: the security picture across runs: counts, the agents that matter, the open security incidents. */
class SecurityRouteTest {

    private static void trifecta(FleetFixtures f, String client, String run, String agent) {
        trifecta(f, client, run, agent, 0);
    }

    private static void trifecta(FleetFixtures f, String client, String run, String agent, double base) {
        f.tool(client, run, agent, "Fetch", "untrusted", null, base + 1);
        f.tool(client, run, agent, "DB", "private", null, base + 2);
        f.tool(client, run, agent, "Send", "outward", null, base + 3);
    }

    @Test
    void anEmptyServerAnswersWithEmptyThings() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            JsonNode a = f.json("/api/security");
            assertThat(a.get("agents")).isEmpty();
            assertThat(a.get("incidents")).isEmpty();
            assertThat(a.get("counts").isObject()).isTrue();
        }
    }

    @Test
    void anAgentThatUsedAllThreeLegsIsListedWithItsRunAndTheTrifectaIncidentIsOpen() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            trifecta(f, "shop", "r1", "Support");
            JsonNode a = f.json("/api/security");
            assertThat(a.get("counts").get("trifectaExercised").asInt()).isEqualTo(1);
            assertThat(a.get("agents")).singleElement().satisfies(r -> {
                assertThat(r.get("agent").asText()).isEqualTo("Support");
                assertThat(r.get("verdict").asText()).isEqualTo("exercised");
                assertThat(r.get("client").asText()).isEqualTo("shop");
                assertThat(r.get("traceId").asText()).isEqualTo(FleetFixtures.trace("r1"));
            });
            assertThat(a.get("incidents")).singleElement().satisfies(i -> {
                assertThat(i.get("kind").asText()).isEqualTo("TRIFECTA");
                assertThat(i.get("severity").asText()).isEqualTo("high");
                assertThat(i.get("traceId").asText()).isEqualTo(FleetFixtures.trace("r1"));
            });
        }
    }

    @Test
    void anAcceptedAgentShowsItsReason() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.accept("shop", "r1", "Support", "customer support: reviewed"); // the rule is in place before the run, as it normally is
            trifecta(f, "shop", "r1", "Support");
            JsonNode a = f.json("/api/security");
            assertThat(a.get("agents").get(0).get("verdict").asText()).isEqualTo("accepted");
            assertThat(a.get("agents").get(0).get("acceptedReason").asText()).isEqualTo("customer support: reviewed");
            assertThat(a.get("counts").get("trifectaAccepted").asInt()).isEqualTo(1);
            assertThat(a.get("incidents").get(0).get("severity").asText()).isEqualTo("info");
        }
    }

    @Test
    void agentsWithNothingNotableAreLeftOutOfTheRowsButStillCounted() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.tool("shop", "r1", "Quiet", "Calc", "", null, 1);
            f.tool("shop", "r1", "Reader", "Fetch", "untrusted", null, 2);
            f.tool("shop", "r1", "Reader", "Fetch", "untrusted", "permission_denied", 3);
            JsonNode a = f.json("/api/security");
            assertThat(a.get("agents")).isEmpty();
            assertThat(a.get("counts").get("accessRefused").asInt()).isEqualTo(1);
        }
    }

    @Test
    void anAgentThatCanDoAllThreeButHasNotIsArmedWhenTheProfileIsKnown() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            f.tool("shop", "r1", "Billing", "Fetch", "untrusted", null, 1);
            f.registry.setSecurityProfile("shop", FleetFixtures.trace("r1"), () -> List.of(java.util.Map.of("agent", "Billing", "untrusted", List.of("Fetch"), "private", List.of("DB"), "outward", List.of("Refund"))));
            JsonNode a = f.json("/api/security");
            assertThat(a.get("agents")).singleElement().satisfies(r -> assertThat(r.get("verdict").asText()).isEqualTo("armed"));
        }
    }

    @Test
    void aClientCanBeAskedFor() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            trifecta(f, "shop", "r1", "Support");
            trifecta(f, "blog", "r2", "Writer");
            JsonNode shop = f.json("/api/security?client=shop");
            assertThat(shop.get("agents")).singleElement().satisfies(r -> assertThat(r.get("agent").asText()).isEqualTo("Support"));
            assertThat(shop.get("counts").get("trifectaExercised").asInt()).isEqualTo(1);
            assertThat(f.json("/api/security?client=nobody").get("agents")).isEmpty();
        }
    }

    @Test
    void theAnswerIsBoundedAndNewestFirst() throws Exception {
        try (FleetFixtures f = new FleetFixtures().start()) {
            for (int i = 0; i < 250; i++) {
                f.runStart("shop", "run" + i);
                trifecta(f, "shop", "run" + i, "Agent", i * 10);
            }
            JsonNode a = f.json("/api/security");
            assertThat(a.get("agents").size()).isEqualTo(200);
            assertThat(a.get("incidents").size()).isEqualTo(100);
            List<String> first = new ArrayList<>();
            a.get("agents").forEach(r -> first.add(r.get("traceId").asText()));
            assertThat(first.get(0)).isEqualTo(FleetFixtures.trace("run249"));
        }
    }
}
