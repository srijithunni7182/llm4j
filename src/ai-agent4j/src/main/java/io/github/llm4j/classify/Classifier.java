package io.github.llm4j.classify;

import io.github.llm4j.LLMClient;
import io.github.llm4j.provider.ClassificationProvider;

/**
 * Answers typed questions about a state: a choice from a set, a yes/no, a point on a scale. The
 * caller never parses text, and every result answers exactly the questions asked with values the
 * request allows (a {@link ChoiceAnswer#label()} is always one of the request's labels).
 *
 * <pre>{@code
 * Classifier classifier = Classifier.using(chatClient);          // any LLMClient
 * ClassifyResult r = classifier.classify(ClassifyRequest.builder()
 *         .state("message", customerMessage)
 *         .choice("intent", "What does the customer want?", "refund", "order_status", "other")
 *         .build());
 * String intent = r.choice("intent").label();
 * }</pre>
 *
 * <p>Implementations are thread-safe when their backend is.
 */
@FunctionalInterface
public interface Classifier {

    /**
     * Answers every question of the request in one call.
     *
     * @throws ClassificationException if the backend's reply is not a valid answer to the request
     * @throws io.github.llm4j.exception.LLMException if the call itself fails (timeout, rate limit,
     *     invalid key)
     */
    ClassifyResult classify(ClassifyRequest request);

    /** A classifier that asks any chat model, with the defaults of {@link LlmClassifier}. */
    static Classifier using(LLMClient client) {
        return LlmClassifier.builder(client).build();
    }

    /**
     * A classifier over a backend that answers typed questions directly, such as Jev. Its results
     * are checked against each request.
     */
    static Classifier using(ClassificationProvider provider) {
        return new ProviderClassifier(provider);
    }

    /**
     * This classifier, reporting every call to the listener (numbers and names only; see {@link
     * ClassifyEvent}).
     */
    default Classifier observed(ClassifyListener listener) {
        return new ObservedClassifier(this, listener);
    }
}
