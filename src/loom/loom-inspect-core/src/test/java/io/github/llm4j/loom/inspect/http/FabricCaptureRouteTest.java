package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.loom.inspect.control.LoopActions;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** R36: the page's two actions reach the code that does them, and what comes back is data. */
class FabricCaptureRouteTest extends LoopRouteFixture {

    @Test
    void captureReturnsTheCaseAsJsonForTheTrace() throws Exception {
        String[] s = signIn();
        HttpResponse<String> r = post("capture", "{\"kind\":\"looping\",\"injection\":true}", s);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode b = JSON.readTree(r.body());
        assertThat(b.get("ok").asBoolean()).isTrue();
        assertThat(b.get("name").asText()).isEqualTo("a.test.yaml");
        assertThat(b.get("text").asText()).isEqualTo(CASE);
        assertThat(asked).singleElement().asString().contains(TRACE).contains("looping true");
    }

    @Test
    void anUnknownKindIsDroppedNotPassedOn() throws Exception {
        post("capture", "{\"kind\":\"../../etc\"}", signIn());
        assertThat(asked).singleElement().asString().contains(" null false ");
    }

    @Test
    void tryAChangePassesWhereAndWhatToChange() throws Exception {
        HttpResponse<String> r =
                post(
                        "whatif",
                        "{\"at\":\"Main/s2\",\"sets\":{\"tier\":\"gold\"},\"answers\":{\"Triage\":\"urgent\"}}",
                        signIn());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("text").asText()).isEqualTo("same result");
        assertThat(asked).singleElement().asString().contains("Main/s2").contains("tier=gold");
    }

    @Test
    void aRunThatIsNotThereIs404AndNothingIsAsked() throws Exception {
        String[] s = signIn();
        HttpResponse<String> r =
                send(
                        "POST",
                        "/api/runs/" + "0".repeat(32) + "/capture",
                        "{}",
                        s[0],
                        s[1],
                        "application/json");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(asked).isEmpty();
    }

    @Test
    void theRunPageSaysWhetherTheActionsAreOnAndTheLoopRouteCountsTests() throws Exception {
        String[] s = signIn();
        JsonNode run =
                JSON.readTree(send("GET", "/api/runs/" + TRACE, null, s[0], null, null).body());
        assertThat(run.at("/loop/enabled").asBoolean()).isTrue();
        JsonNode n =
                JSON.readTree(send("GET", "/api/loop?kind=looping", null, s[0], null, null).body());
        assertThat(n.get("tests").asInt()).isEqualTo(3);
        allowed = false;
        JsonNode off = JSON.readTree(send("GET", "/api/loop", null, s[0], null, null).body());
        assertThat(off.get("enabled").asBoolean()).isFalse();
        assertThat(off.get("reason").asText()).contains("--allow-capture");
    }

    @Test
    void aFailureCarriesItsStatusAndMessage() throws Exception {
        answer = LoopActions.Outcome.failed(422, "masking changes the way it goes");
        HttpResponse<String> r = post("capture", "{}", signIn());
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(r.body()).get("message").asText()).contains("masking");
    }
}
