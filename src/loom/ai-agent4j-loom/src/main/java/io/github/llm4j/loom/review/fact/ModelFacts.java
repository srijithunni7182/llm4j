package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.review.model.Location;
import java.util.Locale;

/** The {@code model} entities, made on demand by whichever provider meets a model id first. */
final class ModelFacts {

    private ModelFacts() {}

    /**
     * The entity for {@code id}, made if it is the first time it is seen; unknown when no model is
     * named.
     */
    static Object of(FactBase.Builder out, String file, String id, int line) {
        if (id == null || id.isBlank()) return Unknown.INSTANCE;
        String key = "model:" + id;
        return out.find(key)
                .orElseGet(
                        () -> {
                            Entity e = out.add("model", key, Location.at(file, line));
                            out.set(e, "id", id);
                            out.set(e, "floatingAlias", floating(id));
                            int slash = id.indexOf('/');
                            out.set(e, "provider", slash > 0 ? id.substring(0, slash) : "");
                            return e;
                        });
    }

    /** An alias that moves under the script's feet, such as {@code gemini-flash-latest}. */
    static boolean floating(String id) {
        String t = id.toLowerCase(Locale.ROOT);
        return t.endsWith("-latest") || t.equals("latest") || t.contains("/latest");
    }
}
