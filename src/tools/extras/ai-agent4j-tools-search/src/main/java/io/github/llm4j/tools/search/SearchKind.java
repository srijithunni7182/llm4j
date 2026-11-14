package io.github.llm4j.tools.search;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * {@code tool Web { use: search  provider: brave  api_key: secret.BRAVE }}: web search through a
 * provider the script names (phase 35, D18). The tool reaches the provider's one host and nothing
 * else; the key is a secret option, never shown; the results are untrusted text.
 */
public final class SearchKind extends GenericKind {

    static final int DEFAULT_RESULTS = 5;
    static final int MAX_RESULTS = 20;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(60);
    static final long MAX_REPLY_BYTES = 1024 * 1024;

    private final List<SearchProvider> providers;

    /** The providers listed by the jars on this kind's class path. */
    public SearchKind() {
        this(discover());
    }

    /** These providers and no others: a seam for tests and for hosts that choose in code. */
    public SearchKind(List<SearchProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    private static List<SearchProvider> discover() {
        List<SearchProvider> out = new ArrayList<>();
        ServiceLoader.load(SearchProvider.class, SearchKind.class.getClassLoader()).forEach(out::add);
        return out;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public Set<String> required() {
        return Set.of("provider");
    }

    @Override
    public Set<String> optional() {
        return Set.of("api_key", "max_results", "timeout");
    }

    @Override
    public Set<String> secrets() {
        return Set.of("api_key");
    }

    /** Everything a search returns was written by whoever published the page. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of(ToolKind.WHOLE_RESULT);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.of(
                true, false, false, false, "web search results from " + options.getOrDefault("provider", "a provider"));
    }

    /** The settings of one declaration, checked. */
    record Config(SearchProvider provider, String apiKey, int maxResults, Duration timeout) {}

    Config config(Options o) {
        String name = o.require("provider");
        SearchProvider provider =
                providers.stream()
                        .filter(p -> p.name().equals(name))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new OptionException(
                                                "provider: no search provider "
                                                        + name
                                                        + " is on the class path ("
                                                        + (providers.isEmpty()
                                                                ? "none is"
                                                                : "known: " + String.join(", ", providers.stream().map(SearchProvider::name).sorted().toList()))
                                                        + "); add the jar that provides it"));
        if (provider.needsKey() && !o.has("api_key"))
            throw new OptionException(
                    "api_key: " + name + " needs a key; give it as a secret, e.g. api_key: secret.SEARCH_KEY");
        return new Config(
                provider,
                o.get("api_key"),
                o.integer("max_results", DEFAULT_RESULTS, 1, MAX_RESULTS),
                o.duration("timeout", DEFAULT_TIMEOUT, MAX_TIMEOUT));
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        config(o);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Config c = config(o);
        String host = c.provider().host();
        NetPolicy policy =
                new NetPolicy(new NetPolicy.Rules(false, false, Set.of(host), false), NetPolicy.Resolver.SYSTEM, host);
        HttpSupport http = new HttpSupport(policy, context, c.timeout(), MAX_REPLY_BYTES, 1);
        return new SearchTool(name, c, http, redactor(o), context);
    }
}
