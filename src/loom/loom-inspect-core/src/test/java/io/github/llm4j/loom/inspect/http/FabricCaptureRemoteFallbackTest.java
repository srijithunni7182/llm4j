package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.inspect.control.LoopActions;
import org.junit.jupiter.api.Test;

/** R37: a journal that is not here is not an error; the page is told what to run. */
class FabricCaptureRemoteFallbackTest extends LoopRouteFixture {

    @Test
    void theCommandComesBackInsteadOfACase() throws Exception {
        answer =
                LoopActions.Outcome.elsewhere(
                        "This server does not hold the run's journal. Run this where the store lives:",
                        "weave capture r-1 --store <where this run's store is> --tag incident:looping");
        var r = post("capture", "{\"kind\":\"looping\"}", signIn());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode b = JSON.readTree(r.body());
        assertThat(b.get("ok").asBoolean()).isFalse();
        assertThat(b.get("fallback").asBoolean()).isTrue();
        assertThat(b.get("command").asText()).startsWith("weave capture r-1");
        assertThat(b.has("text")).isFalse();
    }
}
