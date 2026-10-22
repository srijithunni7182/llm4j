package io.github.llm4j.spring;

import io.github.llm4j.LLMClient;
import io.github.llm4j.audit.AuditLogger;
import io.github.llm4j.audit.FileAuditLogger;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetListener;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.budget.PriceTable;
import io.github.llm4j.budget.TokenEstimator;
import io.github.llm4j.budget.Window;
import io.github.llm4j.privacy.MaskingLLMClient;
import io.github.llm4j.privacy.PIIDetector;
import io.github.llm4j.privacy.PIIType;
import io.github.llm4j.privacy.RegexPIIDetector;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.routing.CostAwareRoutingStrategy;
import io.github.llm4j.routing.FallbackRoutingStrategy;
import io.github.llm4j.routing.ProviderTier;
import io.github.llm4j.routing.RoutingLLMClient;
import io.github.llm4j.secret.SecretStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Provider clients and what wraps them. Each bean is what you would build yourself:
 *
 * <ul>
 *   <li>{@link LLMClients}: {@code new DefaultLLMClient(new AnthropicProvider(config))} and so on,
 *       one per {@code llm4j.clients.<name>};
 *   <li>{@link LLMClient}: the client named by {@code llm4j.primary}, or a {@code RoutingLLMClient}
 *       over {@code llm4j.routing.clients}, wrapped in a {@code BudgetedLLMClient} when a budget is
 *       set, then a {@code MaskingLLMClient} when {@code llm4j.privacy.mask-pii} is true
 *       (outermost, so neither the budget's estimate nor a provider sees unmasked text), then your
 *       {@link LLMClientCustomizer}s;
 *   <li>{@link PriceTable}, {@link Budget}, {@link AuditLogger}.
 * </ul>
 *
 * Nothing here contacts a provider, and each bean backs off to one of your own.
 */
@AutoConfiguration(after = Llm4jSecretsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "llm4j", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(Llm4jProperties.class)
public class Llm4jClientsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @Conditional(Llm4jConditions.ClientsConfigured.class)
    public LLMClients llm4jClients(
            Llm4jProperties properties,
            ObjectProvider<SecretStore> secrets,
            ObjectProvider<Clock> clock,
            ObjectProvider<Sleeper> sleeper) {
        Map<String, LLMClient> clients = new LinkedHashMap<>();
        Map<String, ProviderTier> tiers = new LinkedHashMap<>();
        for (Map.Entry<String, Llm4jProperties.Client> e : properties.namedClients().entrySet()) {
            clients.put(
                    e.getKey(),
                    ClientFactory.create(
                            e.getKey(),
                            e.getValue(),
                            secrets.getIfAvailable(),
                            clock.getIfAvailable(),
                            sleeper.getIfAvailable()));
            if (e.getValue().tier() != null)
                tiers.put(
                        e.getKey(),
                        tier("llm4j.clients." + e.getKey() + ".tier", e.getValue().tier()));
        }
        return new LLMClients(clients, tiers);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(Llm4jConditions.PricingConfigured.class)
    public PriceTable llm4jPriceTable(Llm4jProperties properties, ResourceLoader resources)
            throws IOException {
        Llm4jProperties.Pricing p = properties.pricing();
        if (p.file() != null) {
            Resource resource = resources.getResource(p.file());
            if (!resource.exists())
                throw new Llm4jConfigurationException(
                        "llm4j.pricing.file " + p.file() + " does not exist",
                        "Point it at a prices file with a file: or classpath: prefix (lines of 'model = input / output' per million tokens).");
            if (resource.isFile()) return PriceTable.load(resource.getFile().toPath());
            Path copy = Files.createTempFile("llm4j-prices", ".properties");
            try (var in = resource.getInputStream()) {
                Files.write(copy, in.readAllBytes());
                return PriceTable.load(copy);
            } finally {
                Files.deleteIfExists(copy);
            }
        }
        Map<String, PriceTable.Price> prices = new LinkedHashMap<>();
        p.models()
                .forEach(
                        (model, price) ->
                                prices.put(
                                        model,
                                        new PriceTable.Price(
                                                price.inputPerMillion(),
                                                price.outputPerMillion())));
        return PriceTable.of(prices);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(Llm4jConditions.BudgetConfigured.class)
    public Budget llm4jBudget(
            Llm4jProperties properties,
            ObjectProvider<BudgetListener> listeners,
            ObjectProvider<PriceTable> prices,
            ObjectProvider<Clock> clock) {
        Llm4jProperties.Budget b = properties.budget();
        if (b.cost() != null && prices.getIfAvailable() == null) {
            throw new Llm4jConfigurationException(
                    "llm4j.budget.cost is set but there are no prices to turn tokens into money",
                    "Set llm4j.pricing.file (or llm4j.pricing.models.<model>.input-per-million and output-per-million), or declare a PriceTable bean.");
        }
        Budget.Builder builder = Budget.builder().name("llm4j");
        if (b.tokens() != null) builder.tokens(b.tokens());
        if (b.calls() != null) builder.calls(b.calls());
        if (b.cost() != null)
            builder.cost(new BigDecimal(b.cost().strip().replaceFirst("^\\$", "")));
        if (b.warnAt() != null) builder.warnAt(b.warnAt());
        if (b.window() != null) builder.window(window(b.window()));
        clock.ifAvailable(builder::clock);
        Budget budget = builder.build();
        listeners.orderedStream().forEach(budget::addListener);
        return budget;
    }

    @Bean
    @ConditionalOnMissingBean(LLMClient.class)
    @Conditional(Llm4jConditions.ClientsConfigured.class)
    public LLMClient llmClient(
            LLMClients clients,
            Llm4jProperties properties,
            ObjectProvider<Budget> budget,
            ObjectProvider<PriceTable> prices,
            ObjectProvider<TokenEstimator> estimator,
            ObjectProvider<PIIDetector> detector,
            ObjectProvider<LLMClientCustomizer> customizers) {
        String primary = properties.primary();
        LLMClient client;
        Llm4jProperties.Routing routing = properties.routing();
        String strategy = routing.strategy().trim().toLowerCase(Locale.ROOT);
        if (strategy.equals("none")) {
            if (!clients.names().contains(primary)) {
                throw new Llm4jConfigurationException(
                        "llm4j.primary=" + primary + " is not a configured client",
                        "Use one of: " + String.join(", ", clients.names()) + ".");
            }
            client = clients.get(primary);
        } else {
            if (!List.of("fallback", "cost-aware").contains(strategy)) {
                throw new Llm4jConfigurationException(
                        "llm4j.routing.strategy=" + routing.strategy() + " is not known",
                        "Use none, fallback or cost-aware.");
            }
            if (routing.clients() == null || routing.clients().isEmpty()) {
                throw new Llm4jConfigurationException(
                        "llm4j.routing.strategy=" + strategy + " needs llm4j.routing.clients",
                        "List the clients to route over, for example [cheap, strong].");
            }
            RoutingLLMClient.Builder router =
                    RoutingLLMClient.builder()
                            .strategy(
                                    strategy.equals("fallback")
                                            ? new FallbackRoutingStrategy()
                                            : new CostAwareRoutingStrategy());
            for (String name : routing.clients())
                router.addClient(clients.tier(name), clients.get(name));
            client = router.build();
        }
        Budget cap = budget.getIfAvailable();
        if (cap != null) {
            Llm4jProperties.Client main = properties.namedClients().get(primary);
            BudgetedLLMClient.Builder b = BudgetedLLMClient.builder(client).budget(cap);
            PriceTable table = prices.getIfAvailable();
            if (table != null) b.prices(table);
            if (main != null && main.model() != null) b.model(main.model());
            if (properties.budget().perCallMaxTokens() != null)
                b.perCallCap(properties.budget().perCallMaxTokens());
            TokenEstimator est = estimator.getIfAvailable();
            if (est != null) b.estimator(est);
            client = b.build();
        }
        if (properties.privacy().maskPii()) {
            Set<PIIType> types =
                    properties.privacy().types() == null || properties.privacy().types().isEmpty()
                            ? EnumSet.allOf(PIIType.class)
                            : EnumSet.copyOf(properties.privacy().types());
            client =
                    new MaskingLLMClient(
                            client,
                            detector.getIfAvailable(RegexPIIDetector::new),
                            types,
                            counts -> {});
        }
        for (LLMClientCustomizer c : customizers.orderedStream().toList())
            client = c.customize(client);
        return client;
    }

    @Bean
    @ConditionalOnMissingBean(AuditLogger.class)
    @ConditionalOnProperty(prefix = "llm4j.audit", name = "file")
    public AuditLogger llm4jAuditLogger(Llm4jProperties properties) {
        Llm4jProperties.Audit a = properties.audit();
        Path file = Path.of(a.file());
        if (a.maxFileSize() == null && a.maxFiles() == null) return new FileAuditLogger(file);
        return new FileAuditLogger(
                file,
                a.maxFileSize() == null ? 10L * 1024 * 1024 : a.maxFileSize().toBytes(),
                a.maxFiles() == null ? 5 : a.maxFiles());
    }

    private static ProviderTier tier(String property, String value) {
        try {
            return ProviderTier.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    property + "=" + value + " is not a tier",
                    "Use FAST_CHEAP, BALANCED or REASONING.");
        }
    }

    private static Window window(String value) {
        try {
            return Window.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Llm4jConfigurationException(
                    "llm4j.budget.window=" + value + " is not a window",
                    "Use minute, hour or day (leave it out for a budget that never refills).");
        }
    }
}
