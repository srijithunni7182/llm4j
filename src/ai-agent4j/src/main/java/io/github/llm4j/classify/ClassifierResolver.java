package io.github.llm4j.classify;

import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.typesafe.JevProvider;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns a model name into a {@link Classifier}, so the backend is a one-word change in
 * configuration. Providers are chosen by name everywhere else in llm4j; this is the same idea for
 * classification:
 *
 * <ul>
 *   <li>{@code Jev/jev-latest} (the alias {@code jev}, any case), a bare {@code jev-…} name, or an
 *       alias declared with {@link Builder#jevAlias}: a {@link JevProvider}, calibrated.
 *   <li>Any other name, such as {@code gemini-2.5-flash} or {@code Box/llama3}: handed as written
 *       to the chat clients the host provides ({@link Builder#chat}), and wrapped in an {@link
 *       LlmClassifier}.
 * </ul>
 *
 * <p>The resolver knows nothing of Loom, Spring or the environment: the host (Loom reads {@code
 * provider X { use: … }} declarations, an application reads its own settings) says what a name
 * means by supplying the functions. {@link #fromEnvironment} is the plain-Java host, reading {@code
 * JEV_API_KEY} and optionally {@code JEV_BASE_URL}.
 */
public final class ClassifierResolver {

    private static final String JEV = "jev";

    private final Function<String, LLMConfig> jev;
    private final Map<String, Function<String, LLMConfig>> jevAliases;
    private final Function<String, LLMClient> chat;

    private ClassifierResolver(Builder b) {
        this.jev = b.jev;
        this.jevAliases = Map.copyOf(b.jevAliases);
        this.chat = b.chat;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A resolver for plain Java: Jev with {@code JEV_API_KEY} (and {@code JEV_BASE_URL} if set)
     * read through {@code env}; every other name through {@code chat}, which may be null when only
     * Jev is used.
     */
    public static ClassifierResolver fromEnvironment(
            Function<String, String> env, Function<String, LLMClient> chat) {
        return builder()
                .chat(chat)
                .jev(
                        model -> {
                            LLMConfig.Builder c = LLMConfig.builder().defaultModel(model);
                            String key = env.apply("JEV_API_KEY");
                            if (key != null && !key.isBlank()) c.apiKey(key);
                            String base = env.apply("JEV_BASE_URL");
                            if (base != null && !base.isBlank()) c.baseUrl(base);
                            return c.build();
                        })
                .build();
    }

    /** The classifier for a model name. */
    public Classifier resolve(String model) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("no model given");
        Function<String, LLMConfig> settings = jevSettings(model);
        if (settings != null) {
            return Classifier.using(new JevProvider(settings.apply(modelPart(model))));
        }
        if (isJevName(model)) {
            throw new IllegalArgumentException(
                    "model "
                            + model
                            + " is a Jev model but no Jev settings were given (the key and endpoint): configure them with jev(..) or fromEnvironment(..)");
        }
        if (chat == null) {
            throw new IllegalArgumentException(
                    "model "
                            + model
                            + " is not a Jev model and no chat clients were given: pass chat(..) to resolve chat models");
        }
        return Classifier.using(chat.apply(model));
    }

    /**
     * Whether the name resolves to Jev: it sends the state to TypeSafe AI's hosted API and its
     * probabilities are calibrated.
     */
    public boolean isJev(String model) {
        return model != null && (jevAliases.containsKey(alias(model)) || isJevName(model));
    }

    private Function<String, LLMConfig> jevSettings(String model) {
        Function<String, LLMConfig> declared = jevAliases.get(alias(model));
        if (declared != null) return declared;
        return isJevName(model) ? jev : null;
    }

    private static boolean isJevName(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.startsWith(JEV + "/") || m.startsWith(JEV + "-");
    }

    /** The part before a slash, lower-cased; empty when there is none. */
    private static String alias(String model) {
        int slash = model.indexOf('/');
        return slash < 0 ? "" : model.substring(0, slash).toLowerCase(Locale.ROOT);
    }

    private static String modelPart(String model) {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(slash + 1);
    }

    /** Configures a {@link ClassifierResolver}. */
    public static final class Builder {

        private Function<String, LLMConfig> jev;
        private final Map<String, Function<String, LLMConfig>> jevAliases = new LinkedHashMap<>();
        private Function<String, LLMClient> chat;

        private Builder() {}

        /**
         * The settings (key, endpoint, timeouts) for {@code Jev/<model>} and bare {@code jev-…}
         * names, given the model part of the name.
         */
        public Builder jev(Function<String, LLMConfig> settings) {
            this.jev = settings;
            return this;
        }

        /**
         * A provider the host knows under another name, of kind jev (a script's {@code provider
         * Scorer { use: jev }}): {@code Scorer/<model>}.
         */
        public Builder jevAlias(String alias, Function<String, LLMConfig> settings) {
            jevAliases.put(
                    Objects.requireNonNull(alias, "alias cannot be null").toLowerCase(Locale.ROOT),
                    Objects.requireNonNull(settings, "settings cannot be null"));
            return this;
        }

        /**
         * How to get a chat client for any name that is not Jev, given as written ({@code
         * gemini-2.5-flash}, {@code Box/llama3}).
         */
        public Builder chat(Function<String, LLMClient> clients) {
            this.chat = clients;
            return this;
        }

        public ClassifierResolver build() {
            return new ClassifierResolver(this);
        }
    }
}
