package io.github.llm4j.loom.invoker;

import static io.github.llm4j.loom.invoker.InvokerHarness.done;
import static io.github.llm4j.loom.invoker.InvokerHarness.listRefunds;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.audit.FileAuditLogger;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.RunSuspended;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A token is never written down: not by the run, not by the tool, not when an answer repeats it, not across a pause and a resume. */
class InvokerSecretsSweepTest {

    private static final String SECRET = "SECRETVALUE-9f3a";

    @TempDir
    Path dir;

    /** Everything the run left on disk, in one string. */
    private String disk() throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.readString(p);
                } catch (IOException e) {
                    return "";
                }
            }).collect(Collectors.joining("\n"));
        }
    }

    private static List<String> capture(Logger logger) {
        List<String> lines = new CopyOnWriteArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                lines.add(r.getMessage());
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        return lines;
    }

    @Test
    @Tag("CC-V5.1")
    void nothingTheRunWritesOrShowsHoldsTheToken() throws Exception {
        List<String> log = capture(Logger.getLogger(HarnessExecutor.class.getName()));
        List<String> events = new CopyOnWriteArrayList<>();
        try (InvokerHarness h = new InvokerHarness(dir)) {
            // the API repeats the token in an answer, in an error body, and in a redirect
            h.respond(r -> switch (r.getRequestUrl().queryParameter("who")) {
                case "ok" -> new MockResponse().setBody("you are " + r.getHeader("Authorization"));
                case "err" -> new MockResponse().setResponseCode(500).setBody("failed for " + r.getHeader("Authorization"));
                default -> new MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere?t=" + r.getHeader("Authorization"));
            });
            h.agent("Scout", listRefunds("ok"), listRefunds("err"), listRefunds("redirect"), done("all three: " + "done"));
            HarnessExecutor e = h.executor(InvokerHarness.SCRIPT);
            e.setJournal(new FileRunJournal(dir.resolve("journal.json")));
            e.setAuditLogger(new FileAuditLogger(dir.resolve("audit.log")));
            e.addTraceListener(ev -> events.add(ev.type() + " " + ev.text() + " " + ev.data()));
            e.initialize();

            e.executeWorkflow("Main", Map.of(), InvokerCredentials.of(Map.of("token", "Bearer " + SECRET)));

            assertThat(h.authorizations()).hasSize(3).allMatch(a -> a.equals("Bearer " + SECRET));
            assertThat(String.join("\n", h.modelSaw)).doesNotContain(SECRET).contains("you are ***").contains("failed for ***");
            assertThat(disk()).isNotBlank().doesNotContain(SECRET);
            assertThat(String.join("\n", events)).isNotBlank().doesNotContain(SECRET);
            assertThat(String.join("\n", log)).doesNotContain(SECRET);
            assertThat(e.getContext().getAll().toString()).doesNotContain(SECRET);
        }
    }

    @Test
    @Tag("CC-V5.2")
    @Tag("CC-V5.3")
    void aPausedRunResumesWithAFreshTokenAndNeverRepeatsAFinishedStep() throws Exception {
        String script = """
                tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
                agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
                workflow Main() {
                    delegate "first" to Scout -> first
                    human_prompt "Go on?" -> answer
                    delegate "second" to Scout -> second
                }
                """;
        HumanInterface away = new HumanInterface() {
            @Override
            public String promptHuman(String message) {
                throw new IllegalStateException("needs a step");
            }

            @Override
            public String promptHuman(String stepId, String message) {
                throw new RunSuspended(stepId, message);
            }
        };
        Path journal = dir.resolve("journal.json");
        try (InvokerHarness h = new InvokerHarness(dir)) {
            h.agent("Scout", listRefunds("ann"), done("first done"), listRefunds("bob"), done("second done"));
            HarnessExecutor before = h.executor(script);
            before.setJournal(new FileRunJournal(journal));
            before.setHumanInterface(away);
            before.initialize();
            org.junit.jupiter.api.Assertions.assertThrows(RunSuspended.class,
                    () -> before.executeWorkflow("Main", Map.of(), InvokerCredentials.of(Map.of("token", "Bearer " + SECRET + "-morning"))));
            assertThat(h.authorizations()).containsExactly("Bearer " + SECRET + "-morning");

            HarnessExecutor after = h.executor(script);
            after.setJournal(new FileRunJournal(journal));
            after.setHumanInterface(message -> "go");
            after.initialize();
            after.executeWorkflow("Main", Map.of(), InvokerCredentials.of(Map.of("token", "Bearer " + SECRET + "-evening")));

            assertThat(h.authorizations()).containsExactly("Bearer " + SECRET + "-morning", "Bearer " + SECRET + "-evening");
            assertThat(after.getContext().getVariable("second")).isEqualTo("second done");
            assertThat(disk()).isNotBlank().doesNotContain(SECRET);
        }
    }
}
