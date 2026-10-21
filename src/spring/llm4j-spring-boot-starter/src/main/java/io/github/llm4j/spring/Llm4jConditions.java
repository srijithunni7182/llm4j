package io.github.llm4j.spring;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The conditions that decide whether a capability was asked for. A bean exists only when its
 * property is set.
 */
final class Llm4jConditions {

    private Llm4jConditions() {}

    /**
     * True when any key exists under {@code prefix} (a map property such as {@code llm4j.clients}).
     */
    static boolean anyUnder(org.springframework.core.env.Environment env, String prefix) {
        return Binder.get(env).bind(prefix, Bindable.mapOf(String.class, Object.class)).isBound();
    }

    /**
     * At least one provider client: {@code llm4j.clients.*}, or the v1 shorthand {@code
     * llm4j.provider}.
     */
    static class ClientsConfigured extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            boolean yes =
                    anyUnder(env, "llm4j.clients")
                            || env.containsProperty("llm4j.provider")
                            || env.containsProperty("llm4j.model");
            return yes
                    ? ConditionOutcome.match("a client is configured")
                    : ConditionOutcome.noMatch("no llm4j client is configured");
        }
    }

    /** {@code llm4j.pricing.file} or {@code llm4j.pricing.models.*}. */
    static class PricingConfigured extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            boolean yes =
                    env.containsProperty("llm4j.pricing.file")
                            || anyUnder(env, "llm4j.pricing.models");
            return yes
                    ? ConditionOutcome.match("prices are configured")
                    : ConditionOutcome.noMatch("no prices are configured");
        }
    }

    /** {@code llm4j.tools.custom.*}. */
    static class CustomToolsConfigured extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            return anyUnder(context.getEnvironment(), "llm4j.tools.custom")
                    ? ConditionOutcome.match("guarded tools are configured")
                    : ConditionOutcome.noMatch("no guarded tools");
        }
    }

    static class BudgetConfigured extends AnyNestedCondition {
        BudgetConfigured() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "llm4j.budget", name = "tokens")
        static class Tokens {}

        @ConditionalOnProperty(prefix = "llm4j.budget", name = "calls")
        static class Calls {}

        @ConditionalOnProperty(prefix = "llm4j.budget", name = "cost")
        static class Cost {}
    }
}
