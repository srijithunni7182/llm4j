package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link FactBase} of a review: runs the fact providers in a fixed order, each seeing
 * what the earlier ones added. The order matters: agents exist before the tools that are linked to
 * them, and both before the steps that refer to them.
 */
public final class FactBuilder {

    private final List<FactProvider> providers = new ArrayList<>();

    private FactBuilder() {}

    /** The built-in providers, in the order they must run. */
    public static FactBuilder standard() {
        FactBuilder b = new FactBuilder();
        b.providers.add(new ScriptFacts());
        b.providers.add(new WorkflowFacts());
        b.providers.add(new AgentFacts());
        b.providers.add(new ToolFacts());
        b.providers.add(new ClassifierFacts());
        b.providers.add(new StepFacts());
        return b;
    }

    /**
     * A builder with extra providers after the standard ones (for Java fact providers a team
     * writes).
     */
    public FactBuilder with(FactProvider extra) {
        providers.add(extra);
        return this;
    }

    public FactBase build(ReviewInput input, ReviewSettings settings) {
        FactBase.Builder out = new FactBase.Builder(settings, input.graph() != null);
        FactInput in = new FactInput(input, settings);
        for (FactProvider p : providers) p.provide(in, out);
        return out.build();
    }
}
