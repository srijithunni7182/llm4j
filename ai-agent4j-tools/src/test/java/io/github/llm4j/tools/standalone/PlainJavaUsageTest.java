package io.github.llm4j.tools.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.tools.WebhookKind;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library works with no Loom on the classpath: a plain Java caller builds a tool and gets exactly-once from a journal. */
class PlainJavaUsageTest {

    @TempDir
    Path dir;

    @Test
    void aToolBuiltFromAMapOfOptionsSendsOnceEvenWhenTheSameCallIsRepeated() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
            server.start();
            Map<String, String> options = Map.of("url", server.url("/hook-SECRET1234").toString(), "format", "json");
            assertThat(new WebhookKind().check(options, dir)).isNull();

            RecordingEffects context = new RecordingEffects();
            Tool hook = new WebhookKind().create("Hook", options, dir, context);
            String first = hook.execute(Map.of("text", "Digest is ready"));
            // After a crash the host builds the tool again on the same journal; the same call is a replay, not a second POST.
            Tool afterRestart = new WebhookKind().create("Hook", options, dir, context);
            String second = afterRestart.execute(Map.of("text", "Digest is ready"));

            assertThat(first).doesNotStartWith("Error:").doesNotContain("SECRET1234");
            assertThat(second).contains("already done");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void aToolWithoutAContextStillWorksAndJournalsInMemory() throws Exception {
        assertThat(EffectContext.noop().journal()).isInstanceOf(EffectJournal.class);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200));
            server.start();
            Tool hook = new WebhookKind().create("Hook", Map.of("url", server.url("/h-SECRET5678").toString()), dir);
            assertThat(hook.execute(Map.of("text", "hello"))).doesNotStartWith("Error:");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void optionsThatAreWrongAreReportedWithoutBuildingAnything() {
        assertThat(new WebhookKind().check(Map.of("url", "not a url at all"), dir)).isNotNull();
        assertThat(new WebhookKind().check(Map.of("url", "https://169.254.169.254/x-SECRET9999"), dir)).contains("link-local");
    }
}
