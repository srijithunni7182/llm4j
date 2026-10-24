package io.github.llm4j.loom.inspect.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.inspect.control.LoopActions;
import org.junit.jupiter.api.Test;

/** R39: every ask is recorded (who, which run, when), refusals too, and never the case itself. */
class FabricCaptureAuditTest extends LoopRouteFixture {

    @Test
    void anAcceptedCaptureIsRecordedWithoutItsContent() throws Exception {
        post("capture", "{}", signIn());
        assertThat(audit).hasSize(2); // the sign-in, then the capture
        var e = audit.get(1);
        assertThat(e).containsEntry("action", "capture").containsEntry("run", TRACE);
        assertThat(e).containsEntry("outcome", "accepted").containsEntry("who", "admin");
        assertThat(e.get("address").toString()).startsWith("127.");
        assertThat(e.toString()).doesNotContain("captured:").doesNotContain("status: observed");
    }

    @Test
    void aTryAChangeIsRecordedWithoutItsReport() throws Exception {
        post("whatif", "{\"at\":\"Main/s1\",\"sets\":{\"secret_tier\":\"gold\"}}", signIn());
        var e = audit.get(1);
        assertThat(e).containsEntry("action", "whatif").containsEntry("outcome", "accepted");
        assertThat(e.toString()).doesNotContain("same result").doesNotContain("gold");
    }

    @Test
    void aRefusalForTheMissingPermissionIsRecorded() throws Exception {
        allowed = false;
        post("capture", "{}", signIn());
        assertThat(audit.get(1).get("outcome").toString()).contains("permission is off");
    }

    @Test
    void aBadTokenIsRecorded() throws Exception {
        String[] s = signIn();
        send("POST", "/api/runs/" + TRACE + "/capture", "{}", s[0], "wrong", "application/json");
        assertThat(audit.get(1).get("outcome").toString()).contains("bad CSRF");
    }

    @Test
    void aFailureAndAnElsewhereAreToldApart() throws Exception {
        String[] s = signIn();
        answer = LoopActions.Outcome.failed(422, "no good");
        post("capture", "{}", s);
        answer = LoopActions.Outcome.elsewhere("not here", "weave capture r-1");
        post("capture", "{}", s);
        assertThat(audit.get(1).get("outcome").toString()).startsWith("refused");
        assertThat(audit.get(2)).containsEntry("outcome", "elsewhere");
    }
}
