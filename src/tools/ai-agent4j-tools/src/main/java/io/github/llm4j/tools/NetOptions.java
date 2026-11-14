package io.github.llm4j.tools;

import io.github.llm4j.agent.tool.EffectPolicy;
import java.time.Duration;
import java.util.Set;

/**
 * The options the network kinds ({@code webhook}, {@code http}, and the extras that call a service)
 * share, parsed once.
 *
 * @param timeout how long one request may take, whole
 * @param retries how many times a request may be repeated
 * @param idempotency whether the tool sends the journal's key so a repeat is safe
 * @param onUnknown what a resume does with a call whose outcome is not known
 * @param rules the address rules of the tool's {@link NetPolicy}
 */
public record NetOptions(
        Duration timeout,
        int retries,
        boolean idempotency,
        EffectPolicy.OnUnknown onUnknown,
        NetPolicy.Rules rules) {

    /** The option names, for a kind to list among its optional options. */
    public static final Set<String> NAMES =
            Set.of(
                    "timeout",
                    "retries",
                    "idempotency",
                    "on_unknown",
                    "hosts",
                    "allow_http",
                    "allow_private");
    /** The longest timeout a declaration may ask for. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Reads the shared options.
     *
     * @param o the declaration's options
     * @param defaultRetries retries when the declaration says nothing
     * @param redirectsAllowed whether {@code follow_redirects} is an option of this kind
     * @return the parsed options
     */
    public static NetOptions parse(Options o, int defaultRetries, boolean redirectsAllowed) {
        return new NetOptions(
                o.duration("timeout", Duration.ofSeconds(15), MAX_TIMEOUT),
                o.integer("retries", defaultRetries, 0, 5),
                o.bool("idempotency", false),
                EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")),
                new NetPolicy.Rules(
                        o.bool("allow_http", false),
                        o.bool("allow_private", false),
                        Set.copyOf(o.list("hosts")),
                        redirectsAllowed && o.bool("follow_redirects", false)));
    }

    /**
     * The effect policy these options give a tool.
     *
     * @param maxPerRun the most calls one run may make, or 0 for no limit
     * @return the policy
     */
    public EffectPolicy policy(int maxPerRun) {
        return new EffectPolicy(onUnknown, idempotency, maxPerRun);
    }
}
