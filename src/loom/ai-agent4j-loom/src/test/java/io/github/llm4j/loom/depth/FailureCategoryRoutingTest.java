package io.github.llm4j.loom.depth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 36 (owner's decision on D16 point 3): a rejected credential reaches {@code on_failure}, which
 * can tell it from other failures with {@code _error_category}, so a script can branch on it.
 */
class FailureCategoryRoutingTest {

    @TempDir Path dir;

    private String run(int status) throws Exception {
        try (MockWebServer api = new MockWebServer()) {
            api.enqueue(new MockResponse().setResponseCode(status).setHeader("WWW-Authenticate", "Bearer").setBody("no"));
            api.start();
            Files.writeString(
                    dir.resolve("s.json"),
                    "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");
            String script =
                    "tool A { use: openapi spec: \"s.json\" operation: \"a\" effect: reads base_url: \"http://localhost:"
                            + api.getPort()
                            + "\" allow_http: true retries: 0 auth_header: \"Authorization\" auth_value: env.TOK }\n"
                            + "workflow Main() {\n"
                            + "  set outcome = \"other\"\n"
                            + "  use A() -> r on_failure {\n"
                            + "    alt (_error_category == \"credential_rejected\") { set outcome = \"needs-new-token\" }\n"
                            + "  }\n"
                            + "}\n";
            Harness h = new Harness(dir);
            h.env.put("TOK", "Bearer test-token-0000");
            HarnessExecutor e = h.executor(script, null);
            e.initialize();
            e.executeWorkflow("Main", Map.of());
            return String.valueOf(e.getContext().getAll().get("outcome"));
        }
    }

    @Test
    void aRejectedCredentialIsToldApartFromOtherFailures() throws Exception {
        assertThat(run(401)).isEqualTo("needs-new-token");
    }

    @Test
    void anotherFailureIsCategorisedOther() throws Exception {
        assertThat(run(500)).isEqualTo("other");
    }
}
