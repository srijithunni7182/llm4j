package io.github.llm4j.loom.debug.dap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R40: live models are never started without a cap and the person's confirmation. */
class DapLiveConfirmTest {

    @TempDir Path dir;

    private ObjectNode launch(DapClient c, String... more) throws Exception {
        Path file = dir.resolve("s.loom");
        Files.writeString(file, TravelFixtures.CORPUS.get(0).script());
        ObjectNode a = c.args();
        a.put("script", file.toString()).put("models", "live");
        a.putObject("input").put("topic", "t");
        for (int i = 0; i < more.length; i += 2) a.put(more[i], more[i + 1]);
        return a;
    }

    @Test
    void liveNeedsACapAndAConfirmation() throws Exception {
        try (DapClient c = new DapClient()) {
            c.request("initialize");
            var noCap = c.request("launch", launch(c));
            assertThat(noCap.path("success").asBoolean()).isFalse();
            assertThat(noCap.path("message").asText()).contains("spend money").contains("maxCost");
            var noPrices = c.request("launch", launch(c, "maxCost", "1"));
            assertThat(noPrices.path("message").asText()).contains("prices");
            var unconfirmed = c.request("launch", launch(c, "maxTokens", "500"));
            assertThat(unconfirmed.path("message").asText())
                    .contains("500 tokens")
                    .contains("confirmed");
        }
    }

    @Test
    void confirmedLiveModelsRunUnderTheCapAndAreCounted() throws Exception {
        var fake = new io.github.llm4j.loom.whatif.WhatIfCandidateTest.FakeModels();
        try (DapClient c = new DapClient(fake)) {
            c.request("initialize");
            ObjectNode a = launch(c, "maxTokens", "500");
            a.put("confirmed", true);
            var ok = c.request("launch", a);
            assertThat(ok.path("success").asBoolean()).as(ok.toString()).isTrue();
            c.request("configurationDone");
            c.next(); // entry
            c.request("next", c.args());
            c.next();
            // the model was asked, and the console says what was spent (R42)
            assertThat(fake.models).hasSize(1);
            var stack = c.request("stackTrace", c.args());
            assertThat(stack.path("success").asBoolean()).isTrue();
            c.request("continue", c.args());
            var out = c.next();
            assertThat(out.path("body").path("output").asText()).contains("The run completed");
        }
    }
}
