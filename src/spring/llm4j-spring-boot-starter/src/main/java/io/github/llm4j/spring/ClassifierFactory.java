package io.github.llm4j.spring;

import io.github.llm4j.classify.Classifier;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.ClassificationProvider;
import io.github.llm4j.provider.perplexity.PerplexityDecisionsProvider;
import io.github.llm4j.provider.typesafe.JevProvider;
import io.github.llm4j.ratelimit.Sleeper;
import io.github.llm4j.secret.SecretRef;
import io.github.llm4j.secret.SecretStore;
import java.time.Clock;
import java.util.Locale;

/**
 * Builds a decision-model backend from its settings: the same few lines you would write by hand.
 * Building one never contacts the provider.
 */
final class ClassifierFactory {

    private static final String KNOWN = "jev or perplexity_decisions";

    private ClassifierFactory() {}

    static Classifier create(
            String name,
            Llm4jProperties.Decider c,
            SecretStore secrets,
            Clock clock,
            Sleeper sleeper) {
        String where = "llm4j.classifiers." + name;
        if (c.provider() == null || c.provider().isBlank()) {
            throw new Llm4jConfigurationException(
                    where + ".provider is not set", "Set it to " + KNOWN + ".");
        }
        String provider = c.provider().trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!provider.equals("jev") && !provider.equals("perplexity_decisions")) {
            throw new Llm4jConfigurationException(
                    where + ".provider=" + c.provider() + " is not known",
                    "Use "
                            + KNOWN
                            + ". To classify with a chat model, use an LLMClient and LlmClassifier.");
        }
        SecretRef key =
                CredentialProperty.ref(where + ".api-key", c.apiKey(), c.apiKeySecret(), secrets);
        if (key == null) {
            throw new Llm4jConfigurationException(
                    where + " (" + provider + ") has no API key",
                    "Set "
                            + where
                            + ".api-key-secret to the name of a secret, for example "
                            + (provider.equals("jev") ? "JEV_API_KEY" : "PERPLEXITY_API_KEY")
                            + ", and configure llm4j.secrets.store.");
        }
        LLMConfig.Builder config =
                LLMConfig.builder()
                        .apiKey(key)
                        .retryPolicy(RetryPolicies.from(c.retry(), clock, sleeper));
        if (c.model() != null) config.defaultModel(c.model());
        if (c.baseUrl() != null) config.baseUrl(c.baseUrl());
        if (c.timeout() != null) config.timeout(c.timeout());
        if (c.connectTimeout() != null) config.connectTimeout(c.connectTimeout());
        ClassificationProvider backend =
                provider.equals("jev")
                        ? new JevProvider(config.build())
                        : new PerplexityDecisionsProvider(config.build());
        return Classifier.using(backend);
    }
}
