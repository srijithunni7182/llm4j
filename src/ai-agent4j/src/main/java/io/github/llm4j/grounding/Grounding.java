package io.github.llm4j.grounding;

import io.github.llm4j.agent.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A request for <b>grounding</b>: the provider searches the web inside the model call and returns
 * the sources it used. It is a property of the request, not a tool, because the provider runs it
 * and returns structured sources; the answer comes back with typed citations.
 *
 * <pre>{@code
 * LLMRequest request = LLMRequest.builder()
 *         .addUserMessage("What changed in the WHO guidance on sodium this year?")
 *         .grounding(Grounding.webSearch().maxSearches(3).allowDomains("who.int", "nih.gov").recency(Recency.YEAR).country("IN"))
 *         .build();
 * LLMResponse r = client.chat(request);
 * r.getCitations();   // the sources
 * r.getSearches();    // how many searches ran
 * }</pre>
 *
 * <p>Nothing is grounded unless a request asks. A provider with no search, or one that cannot
 * honour a setting, makes the client refuse the request before any network call, naming the
 * provider and the setting, unless {@link Builder#lenient()} says to drop what it cannot honour or
 * {@link Builder#fallback(Tool)} gives a client-side search tool to use instead. Immutable.
 */
public final class Grounding {

    private static final Pattern DOMAIN =
            Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d+)?(/\\S*)?");
    private static final Pattern COUNTRY = Pattern.compile("[A-Za-z]{2}");

    private final Integer maxSearches;
    private final Set<String> allowDomains;
    private final Set<String> blockDomains;
    private final Recency recency;
    private final String country;
    private final boolean lenient;
    private final Tool fallback;

    private Grounding(Builder b) {
        this.maxSearches = b.maxSearches;
        this.allowDomains = Collections.unmodifiableSet(new LinkedHashSet<>(b.allowDomains));
        this.blockDomains = Collections.unmodifiableSet(new LinkedHashSet<>(b.blockDomains));
        this.recency = b.recency;
        this.country = b.country;
        this.lenient = b.lenient;
        this.fallback = b.fallback;
        if (!allowDomains.isEmpty() && !blockDomains.isEmpty()) {
            throw new IllegalArgumentException(
                    "allowDomains and blockDomains cannot both be set: allow the domains you trust, or block the ones you do not");
        }
    }

    /**
     * Starts a web-search grounding; the builder is accepted wherever a {@code Grounding} is (the
     * request builder builds it).
     */
    public static Builder webSearch() {
        return new Builder();
    }

    /** A cap on provider-side searches for this request, or null. */
    public Integer maxSearches() {
        return maxSearches;
    }

    /** Only search these domains (bare hosts, optionally with a path). Empty when unrestricted. */
    public Set<String> allowDomains() {
        return allowDomains;
    }

    /** Never use these domains. Empty when none. */
    public Set<String> blockDomains() {
        return blockDomains;
    }

    /** How recent sources must be, or null. */
    public Recency recency() {
        return recency;
    }

    /** An upper-case ISO 3166-1 alpha-2 code to bias results toward, or null. */
    public String country() {
        return country;
    }

    /**
     * Whether settings the provider cannot honour are dropped (and listed in the response metadata
     * under {@code grounding.ignored}) instead of refused.
     */
    public boolean lenient() {
        return lenient;
    }

    /** A client-side search tool to use when the provider has no native grounding. */
    public Optional<Tool> fallback() {
        return Optional.ofNullable(fallback);
    }

    /**
     * The settings that are set, in a stable order. {@link #lenient()} and {@link #fallback()} are
     * not settings a provider has to honour.
     */
    public Set<GroundingOption> options() {
        Set<GroundingOption> set = EnumSet.noneOf(GroundingOption.class);
        if (maxSearches != null) set.add(GroundingOption.MAX_SEARCHES);
        if (!allowDomains.isEmpty()) set.add(GroundingOption.ALLOW_DOMAINS);
        if (!blockDomains.isEmpty()) set.add(GroundingOption.BLOCK_DOMAINS);
        if (recency != null) set.add(GroundingOption.RECENCY);
        if (country != null) set.add(GroundingOption.COUNTRY);
        return set;
    }

    /**
     * This grounding without the given settings (what a lenient client sends to a provider that
     * cannot honour them).
     */
    public Grounding without(Set<GroundingOption> dropped) {
        Builder b = toBuilder();
        if (dropped.contains(GroundingOption.MAX_SEARCHES)) b.maxSearches = null;
        if (dropped.contains(GroundingOption.ALLOW_DOMAINS)) b.allowDomains = new ArrayList<>();
        if (dropped.contains(GroundingOption.BLOCK_DOMAINS)) b.blockDomains = new ArrayList<>();
        if (dropped.contains(GroundingOption.RECENCY)) b.recency = null;
        if (dropped.contains(GroundingOption.COUNTRY)) b.country = null;
        return b.build();
    }

    /** A builder holding everything of this grounding. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.maxSearches = maxSearches;
        b.allowDomains = new ArrayList<>(allowDomains);
        b.blockDomains = new ArrayList<>(blockDomains);
        b.recency = recency;
        b.country = country;
        b.lenient = lenient;
        b.fallback = fallback;
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Grounding g)) return false;
        return lenient == g.lenient
                && Objects.equals(maxSearches, g.maxSearches)
                && allowDomains.equals(g.allowDomains)
                && blockDomains.equals(g.blockDomains)
                && recency == g.recency
                && Objects.equals(country, g.country)
                && Objects.equals(fallback, g.fallback);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                maxSearches, allowDomains, blockDomains, recency, country, lenient, fallback);
    }

    @Override
    public String toString() {
        return "Grounding{maxSearches="
                + maxSearches
                + ", allowDomains="
                + allowDomains
                + ", blockDomains="
                + blockDomains
                + ", recency="
                + recency
                + ", country="
                + country
                + ", lenient="
                + lenient
                + ", fallback="
                + (fallback == null ? "none" : fallback.getName())
                + "}";
    }

    /** Builds a {@link Grounding}. */
    public static final class Builder {
        private Integer maxSearches;
        private java.util.List<String> allowDomains = new ArrayList<>();
        private java.util.List<String> blockDomains = new ArrayList<>();
        private Recency recency;
        private String country;
        private boolean lenient;
        private Tool fallback;

        private Builder() {}

        /** At most this many provider-side searches for the request (at least 1). */
        public Builder maxSearches(int max) {
            if (max < 1)
                throw new IllegalArgumentException("maxSearches must be at least 1, got " + max);
            this.maxSearches = max;
            return this;
        }

        /**
         * Search only these domains, written as bare hosts ({@code who.int}); a scheme and a
         * trailing slash are removed. Not with {@link #blockDomains}.
         */
        public Builder allowDomains(String... domains) {
            this.allowDomains = domains(domains);
            return this;
        }

        /** Never use these domains. Not with {@link #allowDomains}. */
        public Builder blockDomains(String... domains) {
            this.blockDomains = domains(domains);
            return this;
        }

        public Builder recency(Recency recency) {
            this.recency = recency;
            return this;
        }

        /** Bias results toward a country, by its ISO 3166-1 alpha-2 code ({@code IN}). */
        public Builder country(String code) {
            if (code == null || !COUNTRY.matcher(code).matches()) {
                throw new IllegalArgumentException(
                        "country must be a two-letter ISO 3166-1 code such as IN, got " + code);
            }
            this.country = code.toUpperCase(Locale.ROOT);
            return this;
        }

        /**
         * Drop the settings a provider cannot honour (listed in the response metadata) instead of
         * refusing the request. Off by default.
         */
        public Builder lenient() {
            this.lenient = true;
            return this;
        }

        /**
         * Use this client-side search tool, offered to the model as a tool call, when the provider
         * has no native grounding. The citations are the URLs in the tool's results. Needs a
         * provider that can call tools.
         */
        public Builder fallback(Tool tool) {
            this.fallback = Objects.requireNonNull(tool, "tool cannot be null");
            return this;
        }

        public Grounding build() {
            return new Grounding(this);
        }

        private static java.util.List<String> domains(String... domains) {
            Objects.requireNonNull(domains, "domains cannot be null");
            java.util.List<String> out = new ArrayList<>();
            for (String raw : domains) {
                String d =
                        raw == null
                                ? ""
                                : raw.strip()
                                        .toLowerCase(Locale.ROOT)
                                        .replaceFirst("^https?://", "")
                                        .replaceFirst("/+$", "");
                if (!DOMAIN.matcher(d).matches())
                    throw new IllegalArgumentException(
                            "not a domain: \"" + raw + "\" (write a bare host such as who.int)");
                if (!out.contains(d)) out.add(d);
            }
            return out;
        }
    }
}
