package io.github.llm4j;

import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.grounding.Grounding;
import io.github.llm4j.grounding.GroundingFallback;
import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.GroundingSupport;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.provider.LLMProvider;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Default implementation of LLMClient that delegates to a configured provider.
 *
 * <p>It is also where a grounded request is checked, once, for every provider: a request the
 * provider cannot serve (no search of its own, or a setting it cannot honour) is refused with an
 * {@link InvalidRequestException} that names the provider and the setting, before any network call,
 * unless the grounding is {@link Grounding#lenient() lenient} (the settings are dropped and listed
 * in the response metadata under {@value #IGNORED}) or carries a {@link Grounding#fallback()
 * fallback} search tool.
 */
public class DefaultLLMClient implements LLMClient {

    /**
     * Response metadata key: the grounding settings the provider could not honour and a lenient
     * grounding dropped.
     */
    public static final String IGNORED = "grounding.ignored";

    private final LLMProvider provider;

    public DefaultLLMClient(LLMProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.provider.validate();
    }

    @Override
    public LLMResponse chat(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        requireToolSupport(request);
        if (request.getGrounding() == null) return provider.chat(request);
        Checked checked = checkGrounding(request);
        if (checked.fallback()) return GroundingFallback.run(provider, checked.request());
        return checked.mark(provider.chat(checked.request()));
    }

    private void requireToolSupport(LLMRequest request) {
        if (!request.getTools().isEmpty() && !provider.supportsToolCalling()) {
            throw new io.github.llm4j.exception.InvalidRequestException(
                    "provider "
                            + provider.getProviderName()
                            + " does not support native tool calling; send the request without tools");
        }
    }

    /** A grounded request after the check: what to send, and what was dropped. */
    private record Checked(LLMRequest request, List<String> ignored, boolean fallback) {

        LLMResponse mark(LLMResponse response) {
            if (ignored.isEmpty()) return response;
            Map<String, Object> metadata = new HashMap<>(response.getMetadata());
            metadata.put(IGNORED, ignored);
            return response.toBuilder().metadata(metadata).build();
        }
    }

    /**
     * The central grounding check (R6): refuses what the provider cannot serve, drops what a
     * lenient grounding allows to be dropped, and decides whether the client-side fallback runs
     * instead.
     */
    private Checked checkGrounding(LLMRequest request) {
        Grounding grounding = request.getGrounding();
        String name = provider.getProviderName();
        if (provider.groundingSupport() == GroundingSupport.NONE) {
            if (grounding.fallback().isPresent()) return new Checked(request, List.of(), true);
            throw new InvalidRequestException(
                    "provider "
                            + name
                            + " does not support grounding; use a provider that does, or give the grounding a fallback search tool"
                            + " (Grounding.webSearch().fallback(tool)), which needs a provider that can call tools");
        }
        Set<GroundingOption> unsupported = EnumSet.noneOf(GroundingOption.class);
        for (GroundingOption option : grounding.options()) {
            if (!provider.groundingOptions().contains(option)) unsupported.add(option);
        }
        if (unsupported.isEmpty()) return new Checked(request, List.of(), false);
        List<String> labels =
                new ArrayList<>(unsupported.stream().map(GroundingOption::label).toList());
        if (!grounding.lenient()) {
            throw new InvalidRequestException(
                    "provider "
                            + name
                            + " cannot honour the grounding "
                            + (labels.size() == 1 ? "setting " : "settings ")
                            + String.join(", ", labels)
                            + "; remove "
                            + (labels.size() == 1 ? "it" : "them")
                            + " or make the grounding lenient() to have "
                            + (labels.size() == 1 ? "it" : "them")
                            + " ignored");
        }
        LLMRequest sent = request.toBuilder().grounding(grounding.without(unsupported)).build();
        return new Checked(sent, labels, false);
    }

    @Override
    public boolean supportsToolCalling() {
        return provider.supportsToolCalling();
    }

    @Override
    public GroundingSupport groundingSupport() {
        return provider.groundingSupport();
    }

    @Override
    public Set<GroundingOption> groundingOptions() {
        return provider.groundingOptions();
    }

    @Override
    public Stream<LLMResponse> chatStream(LLMRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        requireToolSupport(request);
        if (request.getGrounding() == null) return provider.chatStream(request);
        Checked checked = checkGrounding(request);
        if (checked.fallback())
            return GroundingFallback.asStream(GroundingFallback.run(provider, checked.request()));
        if (checked.ignored().isEmpty()) return provider.chatStream(checked.request());
        // the final chunk says what was dropped; chunks still flow as they arrive
        return markLast(provider.chatStream(checked.request()), checked);
    }

    /**
     * The stream with its last chunk marked: one chunk is held back so that the last one is known
     * when it is reached.
     */
    private static Stream<LLMResponse> markLast(Stream<LLMResponse> source, Checked checked) {
        java.util.Iterator<LLMResponse> it = source.iterator();
        java.util.Iterator<LLMResponse> marked =
                new java.util.Iterator<>() {
                    private LLMResponse held = it.hasNext() ? it.next() : null;

                    @Override
                    public boolean hasNext() {
                        return held != null;
                    }

                    @Override
                    public LLMResponse next() {
                        if (held == null) throw new java.util.NoSuchElementException();
                        LLMResponse current = held;
                        held = it.hasNext() ? it.next() : null;
                        return held == null ? checked.mark(current) : current;
                    }
                };
        return java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(marked, 0), false)
                .onClose(source::close);
    }

    /**
     * Returns the name of the underlying provider.
     *
     * @return provider name
     */
    public String getProviderName() {
        return provider.getProviderName();
    }
}
