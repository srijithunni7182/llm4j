package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectPolicy;

import java.time.Duration;
import java.util.Set;

/** The options the network kinds (`webhook`, `http`) share, parsed once. */
record NetOptions(Duration timeout, int retries, boolean idempotency, EffectPolicy.OnUnknown onUnknown, NetPolicy.Rules rules) {

    static final Set<String> NAMES = Set.of("timeout", "retries", "idempotency", "on_unknown", "hosts", "allow_http", "allow_private");
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    static NetOptions parse(Options o, int defaultRetries, boolean redirectsAllowed) {
        return new NetOptions(
                o.duration("timeout", Duration.ofSeconds(15), MAX_TIMEOUT),
                o.integer("retries", defaultRetries, 0, 5),
                o.bool("idempotency", false),
                EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")),
                new NetPolicy.Rules(o.bool("allow_http", false), o.bool("allow_private", false), Set.copyOf(o.list("hosts")),
                        redirectsAllowed && o.bool("follow_redirects", false)));
    }

    EffectPolicy policy(int maxPerRun) {
        return new EffectPolicy(onUnknown, idempotency, maxPerRun);
    }
}
