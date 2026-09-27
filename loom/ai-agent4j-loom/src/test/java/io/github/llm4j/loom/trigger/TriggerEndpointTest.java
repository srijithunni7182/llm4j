package io.github.llm4j.loom.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.resume.TestClock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verification plan V9.10. */
class TriggerEndpointTest {

    final TestClock clock = new TestClock();
    final InMemoryTriggerStore store = new InMemoryTriggerStore();
    final AtomicInteger fired = new AtomicInteger();
    final TriggerRunner runner = new TriggerRunner(store, (t, r) -> {
        fired.incrementAndGet();
        return TriggerTarget.Outcome.done();
    }, clock, "endpoint");

    @Test
    void v9_10_onlyAuthenticatedCallsTick() {
        store.upsert(Trigger.resume("r1", TestClock.T0, "x", 1));
        TriggerEndpoint endpoint = new TriggerEndpoint(runner, () -> "s3cret", null);
        assertThat(endpoint.handle("POST", Map.of())).isEqualTo(new TriggerEndpoint.Response(401, "{\"error\":\"unauthorized\"}"));
        assertThat(endpoint.handle("POST", Map.of("X-Loom-Token", "wrong")).status()).isEqualTo(401);
        assertThat(endpoint.handle("GET", Map.of("X-Loom-Token", "s3cret")).status()).isEqualTo(405);
        assertThat(fired.get()).isZero();
        assertThat(endpoint.handle("POST", Map.of("x-loom-token", "s3cret"))).isEqualTo(new TriggerEndpoint.Response(200, "{\"fired\":1}"));
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void bearerTokensGoToTheVerifier() {
        TriggerEndpoint endpoint = new TriggerEndpoint(runner, null, token -> token.equals("good-oidc"));
        assertThat(endpoint.handle("POST", Map.of("Authorization", "Bearer good-oidc")).status()).isEqualTo(200);
        assertThat(endpoint.handle("POST", Map.of("Authorization", "Bearer forged")).status()).isEqualTo(401);
        assertThat(endpoint.handle("POST", Map.of("Authorization", "Basic abc")).status()).isEqualTo(401);
        TriggerEndpoint throwing = new TriggerEndpoint(runner, null, token -> { throw new IllegalStateException("bad"); });
        assertThat(throwing.handle("POST", Map.of("Authorization", "Bearer x")).status()).isEqualTo(401);
    }

    @Test
    void nothingConfiguredRefusesEverything() {
        TriggerEndpoint none = new TriggerEndpoint(runner, () -> "", null);
        assertThat(none.handle("POST", Map.of("X-Loom-Token", "")).status()).isEqualTo(401);
        TriggerEndpoint env = TriggerEndpoint.fromEnvironment(runner, null);
        assertThat(env.handle("POST", null).status()).isEqualTo(401);
    }
}
