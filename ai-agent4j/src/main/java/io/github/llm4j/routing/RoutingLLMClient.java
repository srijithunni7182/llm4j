package io.github.llm4j.routing;

import io.github.llm4j.LLMClient;
import io.github.llm4j.exception.LLMException;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A facade LLMClient that intelligently routes requests across multiple underlying providers.
 * It provides seamless auto-failover, attempting secondary providers when the primary fails.
 */
public class RoutingLLMClient implements LLMClient {

    private static final Logger logger = LoggerFactory.getLogger(RoutingLLMClient.class);

    private final RoutingStrategy strategy;
    private final Map<ProviderTier, List<LLMClient>> clientsByTier;

    private RoutingLLMClient(Builder builder) {
        this.strategy = builder.strategy != null ? builder.strategy : new FallbackRoutingStrategy();
        this.clientsByTier = new EnumMap<>(ProviderTier.class);
        
        // Deep copy the configured map ensuring no empty lists
        for (Map.Entry<ProviderTier, List<LLMClient>> entry : builder.clientsByTier.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                this.clientsByTier.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        
        if (this.clientsByTier.isEmpty()) {
            throw new IllegalArgumentException("RoutingLLMClient must be configured with at least one LLMClient.");
        }
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // Clone map for the scope of this request so we can remove failed clients and retry
        Map<ProviderTier, List<LLMClient>> availableClients = new EnumMap<>(clientsByTier);
        List<Exception> suppressedExceptions = new ArrayList<>();

        while (!availableClients.isEmpty()) {
            LLMClient clientToTry;
            try {
                clientToTry = strategy.selectClient(request, availableClients);
            } catch (Exception e) {
                // Strategy can't find anything
                break;
            }

            try {
                logger.debug("Routing request to client: {}", clientToTry.getClass().getSimpleName());
                return clientToTry.chat(request);
            } catch (Exception e) {
                logger.warn("RoutingLLMClient: Client {} failed. Failing over. Error: {}", clientToTry.getClass().getSimpleName(), e.getMessage());
                strategy.onFailure(clientToTry, request, e);
                suppressedExceptions.add(e);
                removeClientFromAvailability(availableClients, clientToTry);
            }
        }

        io.github.llm4j.exception.RateLimitException limited = soonestIfAllRateLimited(suppressedExceptions);
        if (limited != null) throw limited;
        LLMException fatal = new LLMException("All available LLM Clients failed to process the request.");
        for (Exception e : suppressedExceptions) {
            fatal.addSuppressed(e);
        }
        throw fatal;
    }

    /** True only when every client supports it, since any of them may end up serving a request. */
    @Override
    public boolean supportsToolCalling() {
        boolean any = false;
        for (List<LLMClient> clients : clientsByTier.values()) {
            for (LLMClient c : clients) {
                if (!c.supportsToolCalling()) return false;
                any = true;
            }
        }
        return any;
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        
        // Similarly clone available clients
        Map<ProviderTier, List<LLMClient>> availableClients = new EnumMap<>(clientsByTier);
        List<Exception> suppressedExceptions = new ArrayList<>();

        while (!availableClients.isEmpty()) {
            LLMClient clientToTry;
            try {
                clientToTry = strategy.selectClient(request, availableClients);
            } catch (Exception e) {
                break;
            }

            try {
                logger.debug("Routing stream request to client: {}", clientToTry.getClass().getSimpleName());
                return clientToTry.chatStream(request);
            } catch (Exception e) {
                logger.warn("RoutingLLMClient: Stream client {} failed initialization. Failing over. Error: {}", clientToTry.getClass().getSimpleName(), e.getMessage());
                strategy.onFailure(clientToTry, request, e);
                suppressedExceptions.add(e);
                removeClientFromAvailability(availableClients, clientToTry);
            }
        }

        LLMException fatal = new LLMException("All available LLM Clients failed to initialize stream.");
        for (Exception e : suppressedExceptions) {
            fatal.addSuppressed(e);
        }
        throw fatal;
    }

    private void removeClientFromAvailability(Map<ProviderTier, List<LLMClient>> availableClients, LLMClient client) {
        availableClients.values().forEach(list -> list.remove(client));
        availableClients.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RoutingStrategy strategy;
        private final Map<ProviderTier, List<LLMClient>> clientsByTier = new EnumMap<>(ProviderTier.class);

        private Builder() {}

        public Builder strategy(RoutingStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder addClient(ProviderTier tier, LLMClient client) {
            Objects.requireNonNull(tier, "tier cannot be null");
            Objects.requireNonNull(client, "client cannot be null");
            this.clientsByTier.computeIfAbsent(tier, k -> new ArrayList<>()).add(client);
            return this;
        }

        public RoutingLLMClient build() {
            return new RoutingLLMClient(this);
        }
    }

    /**
     * When every client failed only because it is rate limited, the request can go through as soon as
     * the first of them resets: report that one, so callers can wait for it rather than give up.
     */
    public static io.github.llm4j.exception.RateLimitException soonestIfAllRateLimited(List<Exception> failures) {
        if (failures.isEmpty()) return null;
        io.github.llm4j.exception.RateLimitException soonest = null;
        for (Exception e : failures) {
            if (!(e instanceof io.github.llm4j.exception.RateLimitException r)) return null;
            if (soonest == null) {
                soonest = r;
            } else if (r.info() != null && (soonest.info() == null
                    || r.info().resetAt().isBefore(soonest.info().resetAt()))) {
                soonest = r;
            }
        }
        return soonest;
    }
}
