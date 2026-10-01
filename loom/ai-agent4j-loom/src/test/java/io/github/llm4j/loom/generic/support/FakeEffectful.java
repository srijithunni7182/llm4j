package io.github.llm4j.loom.generic.support;

import io.github.llm4j.loom.tools.generic.EffectPolicy;
import io.github.llm4j.loom.tools.generic.Effectful;
import io.github.llm4j.loom.tools.generic.Outcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A side-effect tool that only counts what it was asked to do. */
public final class FakeEffectful implements Effectful {

    public final List<Map<String, Object>> performed = Collections.synchronizedList(new ArrayList<>());
    public final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());
    public final String name;
    public EffectPolicy policy = EffectPolicy.DEFAULT;
    public boolean effect = true;
    public Supplier<Outcome> next = () -> Outcome.ok("done");

    public FakeEffectful(String name) {
        this.name = name;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return "fake"; }
    @Override public boolean isEffect(Map<String, Object> args) { return effect; }
    @Override public EffectPolicy policy() { return policy; }
    @Override public String target(Map<String, Object> args) { return "fake-target"; }

    @Override
    public Outcome perform(Map<String, Object> args, String idempotencyKey) {
        performed.add(args);
        idempotencyKeys.add(idempotencyKey);
        return next.get();
    }

    @Override
    public String execute(Map<String, Object> args) {
        return perform(args, null).text();
    }
}
