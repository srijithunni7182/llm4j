package io.github.llm4j.spring;

import static io.github.llm4j.spring.ClientsTest.rootConfigurationProblem;
import static io.github.llm4j.spring.Fixtures.ask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.LLMClient;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.FileAuditLogger;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetEvent;
import io.github.llm4j.budget.BudgetExceeded;
import io.github.llm4j.budget.BudgetListener;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.model.LLMRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prices, the budget, masking and the audit log. V4. */
class DecoratorsTest {

    /** Each call is reported as 10 prompt and 5 completion tokens; the estimate before the call says the same, so a reservation matches what is spent. */
    private static final TokenEstimator FIXED = new TokenEstimator() {
        @Override public long prompt(LLMRequest request) { return 10; }
        @Override public long completion(String content) { return 5; }
    };

    @TempDir
    Path dir;

    private static String[] ollama(Fixtures.Provider p, String... more) {
        List<String> all = new ArrayList<>(List.of("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.clients.default.base-url=" + p.url(), "llm4j.clients.default.retry.enabled=false"));
        all.addAll(List.of(more));
        return all.toArray(new String[0]);
    }

    @Tag("SB-V4.1")
    @Test
    void pricesTurnTokensIntoMoneyFromTheFileOrFromProperties() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            java.nio.file.Files.writeString(dir.resolve("prices.properties"), "m = 3 / 15\n");
            for (String form : List.of("llm4j.pricing.models.m.input-per-million=3", "llm4j.pricing.file=file:" + dir.resolve("prices.properties"))) {
                List<String> props = new ArrayList<>(List.of(ollama(p, "llm4j.budget.tokens=1000000")));
                props.add(form);
                if (form.contains("input-per-million")) props.add("llm4j.pricing.models.m.output-per-million=15");
                Fixtures.runner().withBean(TokenEstimator.class, () -> FIXED).withPropertyValues(props.toArray(new String[0])).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ask(ctx.getBean(LLMClient.class), "hi");
                    assertThat(ctx.getBean(Budget.class).spent().cost()).isEqualByComparingTo(new BigDecimal("0.000105")); // 10 x 3/1e6 + 5 x 15/1e6
                });
            }
        }
    }

    @Tag("SB-V4.2")
    @Test
    void theBudgetRefusesACallBeforeItReachesTheProvider() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            Fixtures.runner().withBean(TokenEstimator.class, () -> FIXED).withPropertyValues(ollama(p, "llm4j.budget.tokens=40", "llm4j.budget.per-call-max-tokens=5")).run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                ask(client, "one");
                ask(client, "two");
                assertThatThrownBy(() -> ask(client, "three")).isInstanceOf(BudgetExceeded.class);
                assertThat(p.seen).hasSize(2);
            });
        }
    }

    @Tag("SB-V4.3")
    @Test
    void aCostCapWithoutPricesIsRefused() {
        Fixtures.runner().withPropertyValues("llm4j.clients.default.provider=ollama", "llm4j.clients.default.model=m", "llm4j.budget.cost=1.00").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootConfigurationProblem(ctx.getStartupFailure()).action()).contains("llm4j.pricing");
        });
    }

    @Tag("SB-V4.4")
    @Test
    void aWindowedBudgetRefillsWhenTheWindowEnds() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            MutableClock clock = new MutableClock("2026-10-15T09:00:00Z");
            Fixtures.runner().withBean(Clock.class, () -> clock).withBean(TokenEstimator.class, () -> FIXED).withPropertyValues(ollama(p, "llm4j.budget.tokens=30", "llm4j.budget.window=hour", "llm4j.budget.per-call-max-tokens=5")).run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                ask(client, "one");
                ask(client, "two");
                clock.set("2026-10-15T09:20:00Z");
                assertThatThrownBy(() -> ask(client, "three")).isInstanceOf(BudgetExceeded.class);
                clock.set("2026-10-15T09:59:59Z");
                assertThatThrownBy(() -> ask(client, "four")).isInstanceOf(BudgetExceeded.class);
                clock.set("2026-10-15T10:00:00Z");
                assertThat(ask(client, "five").getContent()).isEqualTo("hello");
            });
        }
    }

    @Tag("SB-V4.5")
    @Test
    void budgetListenersHearTheWarningOnce() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            List<BudgetEvent> events = Collections.synchronizedList(new ArrayList<>());
            Fixtures.runner().withBean(TokenEstimator.class, () -> FIXED).withBean(BudgetListener.class, () -> events::add)
                    .withPropertyValues(ollama(p, "llm4j.budget.tokens=40", "llm4j.budget.warn-at=0.5", "llm4j.budget.per-call-max-tokens=5")).run(ctx -> {
                LLMClient client = ctx.getBean(LLMClient.class);
                ask(client, "one");
                ask(client, "two");
                assertThat(events).filteredOn(e -> e.kind() == BudgetEvent.Kind.WARNING).hasSize(1);
            });
        }
    }

    @Tag("SB-V4.6")
    @Test
    void aFailoverIsOneCallToTheBudget() throws Exception {
        try (var cheap = new Fixtures.Provider("ollama"); var strong = new Fixtures.Provider("ollama")) {
            cheap.responder = r -> new MockResponse().setResponseCode(503).setBody("down");
            Fixtures.runner().withBean(TokenEstimator.class, () -> FIXED).withPropertyValues("llm4j.clients.cheap.provider=ollama", "llm4j.clients.cheap.model=m",
                    "llm4j.clients.cheap.base-url=" + cheap.url(), "llm4j.clients.cheap.tier=FAST_CHEAP", "llm4j.clients.cheap.retry.enabled=false", "llm4j.clients.strong.provider=ollama",
                    "llm4j.clients.strong.model=m", "llm4j.clients.strong.base-url=" + strong.url(), "llm4j.clients.strong.tier=REASONING", "llm4j.clients.strong.retry.enabled=false",
                    "llm4j.routing.strategy=fallback", "llm4j.routing.clients=cheap,strong", "llm4j.primary=cheap", "llm4j.budget.calls=10").run(ctx -> {
                ask(ctx.getBean(LLMClient.class), "hi");
                assertThat(cheap.seen).hasSize(1);
                assertThat(strong.seen).hasSize(1);
                assertThat(ctx.getBean(Budget.class).spent().calls()).isEqualTo(1);
            });
        }
    }

    @Tag("SB-V4.7")
    @Test
    void personalDataNeverReachesTheProvider() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            Fixtures.runner().withPropertyValues(ollama(p, "llm4j.privacy.mask-pii=true")).run(ctx -> {
                ask(ctx.getBean(LLMClient.class), "Mail jane.doe@example.com or call 415-555-0100 please");
                String body = p.lastBody();
                assertThat(body).doesNotContain("jane.doe@example.com").doesNotContain("415-555-0100").contains("[EMAIL]");
            });
        }
    }

    @Tag("SB-V4.8")
    @Test
    void maskingIsOutsideTheBudgetSoTheEstimatorSeesMaskedText() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            List<String> estimated = Collections.synchronizedList(new ArrayList<>());
            TokenEstimator recording = new TokenEstimator() {
                @Override public long prompt(LLMRequest request) { request.getMessages().forEach(m -> estimated.add(m.getContent())); return 10; }
                @Override public long completion(String content) { return 5; }
            };
            Fixtures.runner().withBean(TokenEstimator.class, () -> recording).withPropertyValues(ollama(p, "llm4j.privacy.mask-pii=true", "llm4j.budget.tokens=1000")).run(ctx -> {
                ask(ctx.getBean(LLMClient.class), "write to jane.doe@example.com");
                assertThat(estimated).isNotEmpty().allSatisfy(t -> assertThat(t).doesNotContain("jane.doe@example.com"));
            });
        }
    }

    @Tag("SB-V4.9")
    @Test
    void onlyTheNamedTypesAreMasked() throws Exception {
        try (var p = new Fixtures.Provider("ollama")) {
            Fixtures.runner().withPropertyValues(ollama(p, "llm4j.privacy.mask-pii=true", "llm4j.privacy.types=EMAIL")).run(ctx -> {
                ask(ctx.getBean(LLMClient.class), "Mail jane.doe@example.com or call 415-555-0100 please");
                assertThat(p.lastBody()).doesNotContain("jane.doe@example.com").contains("415-555-0100");
            });
        }
    }

    @Tag("SB-V4.11")
    @Test
    void anAuditLoggerOfYourOwnWinsAndTheFileOneAppearsOnlyWhenAsked() {
        Fixtures.runner().run(ctx -> assertThat(ctx).doesNotHaveBean(AuditLogger.class));
        Fixtures.runner().withPropertyValues("llm4j.audit.file=" + dir.resolve("audit.jsonl")).run(ctx -> assertThat(ctx.getBean(AuditLogger.class)).isInstanceOf(FileAuditLogger.class));
        AuditLogger mine = new io.github.llm4j.audit.NoOpAuditLogger();
        Fixtures.runner().withBean(AuditLogger.class, () -> mine).withPropertyValues("llm4j.audit.file=" + dir.resolve("audit.jsonl"))
                .run(ctx -> assertThat(ctx.getBean(AuditLogger.class)).isSameAs(mine));
    }
}
