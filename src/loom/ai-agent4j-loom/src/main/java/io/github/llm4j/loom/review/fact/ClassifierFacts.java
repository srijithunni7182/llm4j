package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.review.model.Location;

/** The {@code classifier} entities. */
final class ClassifierFacts implements FactProvider {

    @Override
    public String name() {
        return "classifier";
    }

    @Override
    public void provide(FactInput input, FactBase.Builder out) {
        String file = input.review().file();
        for (IntentClassifierDef c : input.review().script().getIntentClassifiers()) {
            Entity e =
                    out.add(
                            "classifier",
                            "classifier:" + c.getName(),
                            Location.at(file, c.getLine()));
            out.set(e, "name", c.getName());
            out.set(e, "line", (long) c.getLine());
            out.set(e, "model", ModelFacts.of(out, file, c.getModel(), c.getModelLine()));
            out.set(e, "questionCount", (long) c.getQuestions().size());
            out.set(e, "hasConfidenceRule", c.getConfidence() != null);
        }
    }
}
