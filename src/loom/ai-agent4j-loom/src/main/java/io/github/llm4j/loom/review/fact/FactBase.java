package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.review.model.Location;
import io.github.llm4j.loom.review.settings.ReviewSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Every entity found in one script, by kind, plus the project settings rules may read. */
public final class FactBase {

    private final Map<String, List<Entity>> byKind = new LinkedHashMap<>();
    private final Map<String, Entity> byId = new LinkedHashMap<>();
    private final ReviewSettings settings;
    private final boolean graphAvailable;

    private FactBase(ReviewSettings settings, boolean graphAvailable) {
        this.settings = settings;
        this.graphAvailable = graphAvailable;
    }

    /** The entities of one kind, in the order they appear in the script. */
    public List<Entity> entities(String kind) {
        return byKind.getOrDefault(kind, List.of());
    }

    public Optional<Entity> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The value of {@code setting.<key>}: the declared string, or unknown when not declared. */
    public Object setting(String key) {
        return settings.value(key).map(v -> (Object) v).orElse(Unknown.INSTANCE);
    }

    /** False when the graph could not be built, so no step facts exist. */
    public boolean graphAvailable() {
        return graphAvailable;
    }

    /** Collects entities while the providers run. */
    public static final class Builder {
        private final FactBase base;

        public Builder(ReviewSettings settings, boolean graphAvailable) {
            this.base = new FactBase(settings, graphAvailable);
        }

        /** Adds an entity and returns it so its facts can be set. */
        public Entity add(String kind, String id, Location where) {
            Entity e = new Entity(kind, id, where);
            base.byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(e);
            base.byId.put(id, e);
            return e;
        }

        public Optional<Entity> find(String id) {
            return base.find(id);
        }

        /** The entities of one kind added so far. */
        public List<Entity> entitiesOf(String kind) {
            return base.entities(kind);
        }

        /** Sets a fact on an entity. Only providers can; a rule can never change a fact. */
        public Builder set(Entity e, String field, Object value) {
            e.set(field, value);
            return this;
        }

        public FactBase build() {
            return base;
        }
    }
}
