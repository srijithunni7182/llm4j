package io.github.llm4j.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run started for a person acts with that person's credentials, through Spring too, and nothing about them is kept. V10.7. */
class LoomPeopleTest {

    static final String CANARY = "Bearer sk-canary-31c8d2e5";

    @TempDir
    Path dir;

    @Tag("SB-V10.7")
    @Test
    void theToolUsesThePersonsCredentialsAndTheStoreNeverSeesThem() throws Exception {
        List<RecordedRequest> seen = Collections.synchronizedList(new ArrayList<>());
        try (MockWebServer api = new MockWebServer()) {
            api.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    seen.add(request);
                    return new MockResponse().setBody("{\"refunds\": []}");
                }
            });
            api.start();
            Files.writeString(dir.resolve("refunds.json"), """
                    {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"},
                     "servers": [{"url": "%s"}],
                     "paths": {"/refunds": {"get": {"summary": "List refunds", "responses": {"200": {"description": "ok"}}}}}}
                    """.formatted(api.url("/").toString().replaceAll("/$", "")));
            Files.writeString(dir.resolve("support.loom"), """
                    tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
                    agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
                    workflow Main() { delegate "refunds?" to Scout -> x }
                    """);
            LLMClientFactory models = model -> new LLMClient() {
                @Override
                public LLMResponse chat(LLMRequest r) {
                    String all = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                    String reply = all.contains("Observation:")
                            ? "```json\n{\"thought\": \"t\", \"final_answer\": \"done\"}\n```"
                            : "```json\n{\"thought\": \"look\", \"action\": \"Refunds\", \"action_input\": {\"endpoint\": \"/refunds\", \"method\": \"GET\"}}\n```";
                    return LLMResponse.builder().content(reply).model("m").tokenUsage(10, 5, 15).build();
                }

                @Override
                public Stream<LLMResponse> chatStream(LLMRequest r) {
                    return Stream.of(chat(r));
                }
            };
            Fixtures.runner().withBean(LLMClientFactory.class, () -> models).withPropertyValues("llm4j.loom.script=file:" + dir.resolve("support.loom"), "llm4j.loom.audit.fail-on=none",
                    "llm4j.loom.persistence=file", "llm4j.loom.dir=" + dir.resolve("store")).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                RunOutcome out = ctx.getBean(LoomRuntime.class).run(RunRequest.of("Main", Map.of()).withRunId("r-1").as(InvokerCredentials.of(Map.of("token", CANARY))));
                assertThat(out).isInstanceOf(RunOutcome.Completed.class);
            });
        }
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getHeader("Authorization")).isEqualTo(CANARY);
        try (Stream<Path> files = Files.walk(dir.resolve("store"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) assertThat(Files.readString(f)).as(f.toString()).doesNotContain("canary-31c8d2e5");
        }
    }
}
