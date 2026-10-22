package io.github.llm4j.provider.openai;

import io.github.llm4j.grounding.GroundingOption;
import io.github.llm4j.grounding.Recency;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How a Responses-schema vendor does web-search grounding, as data: the tool that turns it on,
 * where each setting goes in it, where the sources and the search count come back. {@link
 * ResponsesRequestWriter} and {@link ResponsesReader} follow it and name no vendor, so a vendor
 * that differs is another value of this class, not another branch.
 *
 * <p>Paths are dotted names into the tool object ({@code filters.allowed_domains}) or, for the
 * response, into the response object ({@code usage.tool_calls_details.search_web.invocation}).
 */
public final class ResponsesGrounding {

    /** Where the sources of an answer are listed. */
    public enum Sources {
        /** {@code url_citation} annotations on the answer text: what the model cites. */
        ANNOTATIONS("cited"),
        /** An output item of its own that lists the results: what the vendor consulted. */
        RESULTS_ITEM("consulted");

        private final String basis;

        Sources(String basis) {
            this.basis = basis;
        }

        /** The value of the {@code grounding.citation_basis} metadata. */
        public String basis() {
            return basis;
        }
    }

    private final String toolType;
    private final Set<GroundingOption> options;
    private final String allowPath;
    private final String blockPath;
    private final String blockPrefix;
    private final int maxAllowDomains;
    private final int maxBlockDomains;
    private final String recencyPath;
    private final Map<Recency, String> recencyValues;
    private final String countryPath;
    private final Map<String, String> countryExtras;
    private final String maxSearchesField;
    private final List<String> include;
    private final Sources sources;
    private final String resultsType;
    private final String callType;
    private final String countPath;
    private final String costPath;

    private ResponsesGrounding(Builder b) {
        this.toolType = b.toolType;
        this.options = Set.copyOf(b.options);
        this.allowPath = b.allowPath;
        this.blockPath = b.blockPath;
        this.blockPrefix = b.blockPrefix;
        this.maxAllowDomains = b.maxAllowDomains;
        this.maxBlockDomains = b.maxBlockDomains;
        this.recencyPath = b.recencyPath;
        this.recencyValues = Map.copyOf(b.recencyValues);
        this.countryPath = b.countryPath;
        this.countryExtras = Map.copyOf(b.countryExtras);
        this.maxSearchesField = b.maxSearchesField;
        this.include = List.copyOf(b.include);
        this.sources = b.sources;
        this.resultsType = b.resultsType;
        this.callType = b.callType;
        this.countPath = b.countPath;
        this.costPath = b.costPath;
    }

    /** The {@code type} of the search tool in the request. */
    public String toolType() {
        return toolType;
    }

    /** The settings of a grounding this vendor can honour. */
    public Set<GroundingOption> options() {
        return options;
    }

    String allowPath() {
        return allowPath;
    }

    String blockPath() {
        return blockPath;
    }

    String blockPrefix() {
        return blockPrefix;
    }

    int maxAllowDomains() {
        return maxAllowDomains;
    }

    int maxBlockDomains() {
        return maxBlockDomains;
    }

    String recencyPath() {
        return recencyPath;
    }

    Map<Recency, String> recencyValues() {
        return recencyValues;
    }

    String countryPath() {
        return countryPath;
    }

    Map<String, String> countryExtras() {
        return countryExtras;
    }

    String maxSearchesField() {
        return maxSearchesField;
    }

    List<String> include() {
        return include;
    }

    Sources sources() {
        return sources;
    }

    String resultsType() {
        return resultsType;
    }

    String callType() {
        return callType;
    }

    String countPath() {
        return countPath;
    }

    String costPath() {
        return costPath;
    }

    public static Builder builder(String toolType) {
        return new Builder(toolType);
    }

    /** Builds a {@link ResponsesGrounding}. */
    public static final class Builder {
        private final String toolType;
        private final Set<GroundingOption> options = EnumSet.noneOf(GroundingOption.class);
        private String allowPath;
        private String blockPath;
        private String blockPrefix;
        private int maxAllowDomains = Integer.MAX_VALUE;
        private int maxBlockDomains = Integer.MAX_VALUE;
        private String recencyPath;
        private final Map<Recency, String> recencyValues = new EnumMap<>(Recency.class);
        private String countryPath;
        private final Map<String, String> countryExtras = new LinkedHashMap<>();
        private String maxSearchesField;
        private final List<String> include = new ArrayList<>();
        private Sources sources = Sources.ANNOTATIONS;
        private String resultsType;
        private String callType;
        private String countPath;
        private String costPath;

        private Builder(String toolType) {
            this.toolType = toolType;
        }

        /** Domains to search go to {@code path}, at most {@code max} of them. */
        public Builder allowDomains(String path, int max) {
            this.allowPath = path;
            this.maxAllowDomains = max;
            options.add(GroundingOption.ALLOW_DOMAINS);
            return this;
        }

        /** Domains never to search go to {@code path}, at most {@code max} of them. */
        public Builder blockDomains(String path, int max) {
            this.blockPath = path;
            this.maxBlockDomains = max;
            options.add(GroundingOption.BLOCK_DOMAINS);
            return this;
        }

        /**
         * Blocked domains go in the list of allowed ones, spelled with {@code prefix} ({@code -}),
         * at most {@code max} in all.
         */
        public Builder blockDomainsWithPrefix(String prefix, int max) {
            this.blockPrefix = prefix;
            this.maxBlockDomains = max;
            options.add(GroundingOption.BLOCK_DOMAINS);
            return this;
        }

        public Builder recency(String path, Map<Recency, String> values) {
            this.recencyPath = path;
            this.recencyValues.putAll(values);
            options.add(GroundingOption.RECENCY);
            return this;
        }

        /** The country goes to {@code path}; {@code extras} are set alongside it. */
        public Builder country(String path, Map<String, String> extras) {
            this.countryPath = path;
            this.countryExtras.putAll(extras);
            options.add(GroundingOption.COUNTRY);
            return this;
        }

        /** The cap on searches is the request field {@code field}. */
        public Builder maxSearches(String field) {
            this.maxSearchesField = field;
            options.add(GroundingOption.MAX_SEARCHES);
            return this;
        }

        /** Values added to the request's {@code include} list when grounding is on. */
        public Builder include(List<String> values) {
            this.include.addAll(values);
            return this;
        }

        /** Sources are the {@code url_citation} annotations on the answer. */
        public Builder citedSources() {
            this.sources = Sources.ANNOTATIONS;
            return this;
        }

        /** Sources are the results of the output item of type {@code type}. */
        public Builder consultedSources(String type) {
            this.sources = Sources.RESULTS_ITEM;
            this.resultsType = type;
            return this;
        }

        /**
         * The output items that are one search each (counted when {@link #countPath} names no
         * field).
         */
        public Builder callItems(String type) {
            this.callType = type;
            return this;
        }

        /** The number of searches the vendor reports, at this path in the response. */
        public Builder countPath(String path) {
            this.countPath = path;
            return this;
        }

        /** What the searches cost in US dollars, as the vendor reports it, at this path. */
        public Builder costPath(String path) {
            this.costPath = path;
            return this;
        }

        public ResponsesGrounding build() {
            return new ResponsesGrounding(this);
        }
    }
}
