package io.github.llm4j.spring;

import static io.github.llm4j.spring.Fixtures.ask;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.LLMClient;
import io.github.llm4j.config.RetryPolicy;
import io.github.llm4j.exception.RateLimitException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.Message;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.secret.InMemorySecretStore;
import io.github.llm4j.secret.SecretStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Named clients, the primary client, routing and retries. V3. */
class ClientsTest {

    private static SecretStore keys(String... names) {
        InMemorySecretStore store = new InMemorySecretStore();
        for (String n : names) store.put(n, "key-" + n);
        return store;
    }

    @Tag("SB-V3.1")
    @Test
    void eachProviderIsReachedOnItsOwnServerAndPath() throws Exception {
        try (var gemini = new Fixtures.Provider("gemini"); var anthropic = new Fixtures.Provider("anthropic"); var ollama = new Fixtures.Provider("ollama"); var sarvam = new Fixtures.Provider("sarvam")) {
            Fixtures.runner().withBean(SecretStore.class, () -> keys("G", "A", "S")).withPropertyValues(
                    "llm4j.clients.g.provider=gemini", "llm4j.clients.g.model=gemini-2.5-flash", "llm4j.clients.g.api-key-secret=G", "llm4j.clients.g.base-url=" + gemini.url(),
                    "llm4j.clients.a.provider=anthropic", "llm4j.clients.a.model=claude-x", "llm4j.clients.a.api-key-secret=A", "llm4j.clients.a.base-url=" + anthropic.url(),
                    "llm4j.clients.o.provider=ollama", "llm4j.clients.o.model=m", "llm4j.clients.o.model=llama3", "llm4j.clients.o.base-url=" + ollama.url(),
                    "llm4j.clients.s.provider=sarvam", "llm4j.clients.s.model=sarvam-m", "llm4j.clients.s.api-key-secret=S", "llm4j.clients.s.base-url=" + sarvam.url(),
                    "llm4j.primary=a", "llm4j.clients.a.retry.enabled=false", "llm4j.clients.g.retry.enabled=false", "llm4j.clients.o.retry.enabled=false", "llm4j.clients.s.retry.enabled=false")
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        LLMClients all = ctx.getBean(LLMClients.class);
                        for (String name : List.of("g", "a", "o", "s")) assertThat(ask(all.get(name), "hi").getContent()).as(name).isEqualTo("hello");
                        assertThat(gemini.seen).hasSize(1);
                        assertThat(gemini.lastPath()).contains(":generateContent");
                        assertThat(anthropic.seen).hasSize(1);
                        assertThat(anthropic.lastPath()).isEqualTo("/v1/messages");
                        assertThat(ollama.seen).hasSize(1);
                        assertThat(ollama.lastPath()).isEqualTo("/api/chat");
                        assertThat(sarvam.seen).hasSize(1);
                        assertThat(sarvam.lastPath()).isEqualTo("/v1/chat/completions");
                    });
        }
    }

    @Tag("SB-V3.2")
    @Tag("SB-V3.3")
    @Test
    void namesKeepTheirOrderAndOnlyThePrimaryIsAnLLMClientBean() throws Exception {
        try (var strong = new Fixtures.Provider("ollama"); var cheap = new Fixtures.Provider("ollama")) {
            strong.reply = "from strong";
            cheap.reply = "from cheap";
            Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + cheap.url(),
                    "llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m", "llm4j.clients.cheap.base-url=" + cheap.url(),
                    "llm4j.clients.strong.provider=ollama", "llm4j.clients.strong.model=m", "llm4j.clients.strong.base-url=" + strong.url(), "llm4j.primary=strong").run(ctx -> {
                assertThat(ctx.getBean(LLMClients.class).names()).containsExactly("default", "cheap", "strong");
                assertThat(ctx.getBeansOfType(LLMClient.class)).hasSize(1);
                assertThat(ask(ctx.getBean(LLMClient.class), "hi").getContent()).isEqualTo("from strong");
            });
        }
    }

    @Tag("SB-V3.4")
    @Test
    void anUnknownPrimaryListsTheConfiguredNames() {
        Fixtures.runner().withPropertyValues("llm4j.clients.a.provider=ollama", "llm4j.clients.a.model=m", "llm4j.clients.b.provider=ollama", "llm4j.clients.b.model=m", "llm4j.primary=missing").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure())).hasMessageContaining("llm4j.primary=missing").extracting(Llm4jConfigurationException::action).asString().contains("a, b");
        });
    }

    @Tag("SB-V3.5")
    @Test
    void aFailingTierFallsOverToTheNext() throws Exception {
        try (var cheap = new Fixtures.Provider("ollama"); var strong = new Fixtures.Provider("ollama")) {
            cheap.responder = r -> new MockResponse().setResponseCode(503).setBody("down");
            strong.reply = "from strong";
            Fixtures.runner().withPropertyValues("llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m", "llm4j.clients.cheap.base-url=" + cheap.url(), "llm4j.clients.cheap.tier=FAST_CHEAP",
                    "llm4j.clients.cheap.retry.enabled=false", "llm4j.clients.strong.provider=ollama", "llm4j.clients.strong.model=m", "llm4j.clients.strong.base-url=" + strong.url(),
                    "llm4j.clients.strong.tier=REASONING", "llm4j.clients.strong.retry.enabled=false", "llm4j.routing.strategy=fallback", "llm4j.routing.clients=cheap,strong").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ask(ctx.getBean(LLMClient.class), "hi").getContent()).isEqualTo("from strong");
                assertThat(cheap.seen).hasSize(1);
                assertThat(strong.seen).hasSize(1);
            });
        }
    }

    @Tag("SB-V3.6")
    @Test
    void costAwareRoutingChoosesTheTierTheRequestNeeds() throws Exception {
        try (var cheap = new Fixtures.Provider("ollama"); var strong = new Fixtures.Provider("ollama")) {
            cheap.reply = "from cheap";
            strong.reply = "from strong";
            Fixtures.runner().withPropertyValues("llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m", "llm4j.clients.cheap.base-url=" + cheap.url(), "llm4j.clients.cheap.tier=FAST_CHEAP",
                    "llm4j.clients.strong.provider=ollama", "llm4j.clients.strong.model=m", "llm4j.clients.strong.base-url=" + strong.url(), "llm4j.clients.strong.tier=REASONING",
                    "llm4j.routing.strategy=cost-aware", "llm4j.routing.clients=cheap,strong").run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                LLMRequest simple = LLMRequest.builder().addMessage(Message.user("hi")).complexityHint(io.github.llm4j.model.ComplexityHint.LOW).build();
                LLMRequest hard = LLMRequest.builder().addMessage(Message.user("hi")).complexityHint(io.github.llm4j.model.ComplexityHint.HIGH).build();
                assertThat(client.chat(simple).getContent()).isEqualTo("from cheap");
                assertThat(client.chat(hard).getContent()).isEqualTo("from strong");
            });
        }
    }

    @Tag("SB-V3.7")
    @Test
    void nothingIsCreatedWithoutAClient() {
        Fixtures.runner().run(ctx -> assertThat(ctx).doesNotHaveBean(LLMClient.class).doesNotHaveBean(LLMClients.class));
    }

    @Tag("SB-V3.8")
    @Test
    void aMissingKeyAndAnUnknownProviderAreNamed() {
        Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=gemini").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("llm4j.clients.default.api-key-secret");
        });
        Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=openai").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("gemini, anthropic, ollama or sarvam");
        });
    }

    @Tag("SB-V3.9")
    @Test
    void retriesFollowTheSettingsWithTheirBackoff() throws Exception {
        try (var ollama = new Fixtures.Provider("ollama")) {
            List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
            ollama.responder = r -> ollama.seen.size() < 3 ? new MockResponse().setResponseCode(500).setBody("oops") : ollama.ok("finally");
            Fixtures.runner().withBean(Sleeper.class, () -> slept::add).withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + ollama.url(),
                    "llm4j.clients.default.retry.max-retries=2", "llm4j.clients.default.retry.initial-backoff=1s").run(ctx -> {
                assertThat(ask(ctx.getBean(LLMClient.class), "hi").getContent()).isEqualTo("finally");
                assertThat(ollama.seen).hasSize(3);
                assertThat(slept).hasSize(2);
                assertThat(slept.get(0)).isBetween(Duration.ofMillis(500), Duration.ofMillis(1500));
                assertThat(slept.get(1)).isBetween(Duration.ofMillis(1000), Duration.ofMillis(3000));
            });
        }
    }

    @Tag("SB-V3.10")
    @Test
    void disablingRetriesSendsOneRequest() throws Exception {
        try (var ollama = new Fixtures.Provider("ollama")) {
            ollama.responder = r -> new MockResponse().setResponseCode(500).setBody("oops");
            Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + ollama.url(), "llm4j.clients.default.retry.enabled=false").run(ctx -> {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ask(ctx.getBean(LLMClient.class), "hi")).isInstanceOf(RuntimeException.class);
                assertThat(ollama.seen).hasSize(1);
            });
        }
    }

    @Tag("SB-V3.11")
    @Tag("SB-V3.13")
    @Test
    void aLongRateLimitIsHandedUpWithItsResetTimeFromTheClockBean() throws Exception {
        try (var ollama = new Fixtures.Provider("ollama")) {
            Instant now = Instant.parse("2026-10-15T09:00:00Z");
            List<Duration> slept = Collections.synchronizedList(new ArrayList<>());
            ollama.responder = r -> ollama.seen.size() == 1 ? new MockResponse().setResponseCode(429).setHeader("Retry-After", "5").setBody("slow")
                    : ollama.seen.size() == 2 ? ollama.ok("after the wait") : new MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("slow");
            Fixtures.runner().withBean(Clock.class, () -> Clock.fixed(now, ZoneOffset.UTC)).withBean(Sleeper.class, () -> slept::add)
                    .withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + ollama.url()).run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                assertThat(ask(client, "hi").getContent()).isEqualTo("after the wait");
                assertThat(slept).hasSize(1);
                assertThat(slept.get(0)).isBetween(Duration.ofSeconds(5), Duration.ofMillis(5500));
                int before = ollama.seen.size();
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ask(client, "again")).isInstanceOfSatisfying(RateLimitException.class,
                        e -> assertThat(e.info().resetAt()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z")));
                assertThat(ollama.seen).hasSize(before + 1); // not retried: the wait is too long to hold a thread
            });
        }
    }

    @Tag("SB-V3.12")
    @Test
    void withNoRetrySettingsThePolicyIsTheLibrarysDefault() {
        assertThat(RetryPolicies.from(null, null, null)).isEqualTo(RetryPolicy.defaultPolicy());
        assertThat(RetryPolicies.from(new Llm4jProperties.Retry(null, null, null, null, null, null, null, null, null), null, null)).isEqualTo(RetryPolicy.defaultPolicy());
    }

    @Tag("SB-V3.14")
    @Test
    void customizersWrapThePrimaryInOrder() throws Exception {
        try (var ollama = new Fixtures.Provider("ollama")) {
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            Fixtures.runner().withBean("second", LLMClientCustomizer.class, () -> new Ordered(2, calls)).withBean("first", LLMClientCustomizer.class, () -> new Ordered(1, calls))
                    .withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + ollama.url()).run(ctx -> {
                ask(ctx.getBean(LLMClient.class), "hi");
                assertThat(calls).containsExactly("2 in", "1 in", "1 out", "2 out"); // the one declared with the higher order wraps the other
            });
        }
    }

    @Tag("SB-V3.15")
    @Test
    void buildingEveryClientContactsNoProvider() throws Exception {
        try (var gemini = new Fixtures.Provider("gemini"); var anthropic = new Fixtures.Provider("anthropic")) {
            Fixtures.runner().withBean(SecretStore.class, () -> keys("G", "A")).withPropertyValues("llm4j.clients.g.provider=gemini", "llm4j.clients.g.api-key-secret=G", "llm4j.clients.g.base-url=" + gemini.url(),
                    "llm4j.clients.a.provider=anthropic", "llm4j.clients.a.api-key-secret=A", "llm4j.clients.a.base-url=" + anthropic.url(), "llm4j.primary=g").run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(gemini.seen).isEmpty();
                assertThat(anthropic.seen).isEmpty();
            });
        }
    }

    /** Wraps a client and records the order calls pass through the layers. */
    @org.springframework.core.annotation.Order(0)
    private static final class Ordered implements LLMClientCustomizer, org.springframework.core.Ordered {
        private final int order;
        private final List<String> calls;

        Ordered(int order, List<String> calls) {
            this.order = order;
            this.calls = calls;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public LLMClient customize(LLMClient client) {
            return new LLMClient() {
                @Override
                public io.github.llm4j.model.LLMResponse chat(LLMRequest request) {
                    calls.add(order + " in");
                    try {
                        return client.chat(request);
                    } finally {
                        calls.add(order + " out");
                    }
                }

                @Override
                public java.util.stream.Stream<io.github.llm4j.model.LLMResponse> chatStream(LLMRequest request) {
                    return client.chatStream(request);
                }
            };
        }
    }

    static Llm4jConfigurationException rootConfigurationProblem(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) if (t instanceof Llm4jConfigurationException e) return e;
        throw new AssertionError("no Llm4jConfigurationException in " + failure, failure);
    }
}
