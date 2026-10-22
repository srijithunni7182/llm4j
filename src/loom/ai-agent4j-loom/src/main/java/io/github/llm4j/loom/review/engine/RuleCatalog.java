package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The rules a review knows about, by id, so that {@code weave review --explain RULE} can say what
 * one is.
 */
public final class RuleCatalog {

    private final Map<String, RuleInfo> byId = new LinkedHashMap<>();

    /**
     * Adds rules; the first one registered for an id wins, so a later source cannot redefine it.
     */
    public RuleCatalog addAll(Collection<RuleInfo> rules) {
        for (RuleInfo r : rules) byId.putIfAbsent(r.id(), r);
        return this;
    }

    public Optional<RuleInfo> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Every known rule, in registration order. */
    public List<RuleInfo> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * A hint such as {@code (did you mean 'LA01'?)} for an id that is not known, or an empty
     * string.
     */
    public String hint(String id) {
        return Suggest.hint(id, byId.keySet());
    }
}
