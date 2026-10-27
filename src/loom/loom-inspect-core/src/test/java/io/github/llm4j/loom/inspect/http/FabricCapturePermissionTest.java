package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * R39: capture exposes run content, so it needs a session, the page's token, and the permission.
 */
class FabricCapturePermissionTest extends LoopRouteFixture {

    @Test
    void notSignedInIs401() throws Exception {
        assertThat(post("capture", "{}", null).statusCode()).isEqualTo(401);
        assertThat(post("whatif", "{}", null).statusCode()).isEqualTo(401);
        assertThat(asked).isEmpty();
    }

    @Test
    void aMissingOrWrongTokenIs403() throws Exception {
        String[] s = signIn();
        assertThat(
                        send(
                                        "POST",
                                        "/api/runs/" + TRACE + "/capture",
                                        "{}",
                                        s[0],
                                        null,
                                        "application/json")
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        send(
                                        "POST",
                                        "/api/runs/" + TRACE + "/capture",
                                        "{}",
                                        s[0],
                                        "wrong",
                                        "application/json")
                                .statusCode())
                .isEqualTo(403);
        assertThat(asked).isEmpty();
    }

    @Test
    void aFormPostIs403() throws Exception {
        String[] s = signIn();
        HttpResponse<String> r =
                send(
                        "POST",
                        "/api/runs/" + TRACE + "/capture",
                        "kind=x",
                        s[0],
                        s[1],
                        "application/x-www-form-urlencoded");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(asked).isEmpty();
    }

    @Test
    void withoutThePermissionASignedInAdministratorIsRefused() throws Exception {
        allowed = false;
        HttpResponse<String> r = post("capture", "{}", signIn());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("--allow-capture");
        assertThat(asked).isEmpty();
    }

    @Test
    void aGetIsNotAnAction() throws Exception {
        String[] s = signIn();
        HttpResponse<String> r =
                send("GET", "/api/runs/" + TRACE + "/capture", null, s[0], s[1], null);
        assertThat(r.statusCode()).isIn(404, 405);
        assertThat(asked).isEmpty();
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        HttpResponse<String> r =
                post("capture", "{\"kind\":\"" + "a".repeat(20_000) + "\"}", signIn());
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(asked).isEmpty();
    }
}
