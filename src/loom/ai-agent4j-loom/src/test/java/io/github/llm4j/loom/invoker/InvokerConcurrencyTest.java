package io.github.llm4j.loom.invoker;

import static io.github.llm4j.loom.invoker.InvokerHarness.done;
import static io.github.llm4j.loom.invoker.InvokerHarness.listRefunds;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two people's workflows at the same time, sharing the worker threads: each acts as itself and never as the other. */
class InvokerConcurrencyTest {

    private static final Pattern WHO = Pattern.compile("refunds for (\\w+)");

    private static final String SCRIPT = """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Scout { model: "m"  system: "You are Scout."  tools: [Refunds] }
            agent Scout2 { model: "m"  system: "You are Scout2."  tools: [Refunds] }
            workflow Main(who) {
                parallel {
                    delegate "look up refunds for {who}" to Scout -> a
                    delegate "look up refunds for {who}" to Scout2 -> b
                }
                broadcast "list refunds for {who}" to [Scout, Scout2] -> c
            }
            """;

    @TempDir
    Path dir;

    @Test
    @Tag("CC-V3.1")
    void everyRequestCarriesTheTokenOfTheRunThatMadeIt() throws Exception {
        try (InvokerHarness h = new InvokerHarness(dir)) {
            h.thinks(conversation -> {
                String all = String.join("\n", conversation);
                if (all.contains("Observation:")) return done("ok");
                Matcher m = WHO.matcher(all);
                if (!m.find()) throw new IllegalStateException("no user in " + all);
                return listRefunds(m.group(1));
            });
            int rounds = 25;
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Void>> users = new ArrayList<>();
            for (String user : List.of("ann", "bob")) {
                InvokerCredentials who = InvokerCredentials.of(Map.of("token", "Bearer token-of-" + user));
                users.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                    for (int i = 0; i < rounds; i++) {
                        HarnessExecutor e = h.executor(SCRIPT); // one executor for each run: a finished run's steps are not run again
                        e.initialize();
                        e.executeWorkflow("Main", Map.of("who", user), who);
                    }
                }));
            }
            go.countDown();
            CompletableFuture.allOf(users.toArray(new CompletableFuture[0])).get();

            List<RecordedRequest> seen;
            synchronized (h.requests) {
                seen = List.copyOf(h.requests);
            }
            assertThat(seen).hasSize(2 * rounds * 4); // each run: two branches and two broadcast agents
            for (RecordedRequest r : seen) {
                String asked = r.getRequestUrl().queryParameter("who");
                assertThat(r.getHeader("Authorization")).as("request for %s", asked).isEqualTo("Bearer token-of-" + asked);
            }
            assertThat(seen.stream().map(r -> r.getRequestUrl().queryParameter("who")).distinct()).containsExactlyInAnyOrder("ann", "bob");
        }
    }
}
