package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.SchemaDef;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a value came from, kept beside the values and never inside them (docs/specs/26-policy-
 * boundary, R7, R15). The runtime tags every variable, and every {@code var.field} path it knows
 * more about, with one of three levels:
 *
 * <ul>
 *   <li>{@link Level#NOT_MODEL}: no model wrote the text (an input, a constant, a label, a number, a
 *       person's answer, the result of a task that uses no model);
 *   <li>{@link Level#CHECKED}: a model wrote it and it passed a {@code check} against the named
 *       policies, was rewritten and re-checked, or a person sent it as it was;
 *   <li>{@link Level#UNCHECKED}: a model wrote it and nothing has judged it.
 * </ul>
 *
 * <p><b>Unknown means unchecked.</b> A value with no recorded origin (a variable a host put in, a
 * route nobody tagged) is treated as unchecked model text, so a forgotten route blocks something it
 * should not, visibly, instead of leaking.
 *
 * <p>A tag can be narrower than "the whole value": {@code textFields} names the fields of a record
 * that hold model-written text; the others (numbers, true/false, enum words) are not model text.
 */
final class Provenance {

    /** How far a value is from being safe to send out. Ordered: a later level is worse. */
    enum Level {
        NOT_MODEL,
        CHECKED,
        UNCHECKED
    }

    /**
     * A value's origin.
     *
     * @param level the level above
     * @param checkedAgainst for {@link Level#CHECKED}: the policies it passed
     * @param textFields for a record: the fields that hold model text, or null meaning all of it
     */
    record Tag(Level level, Set<String> checkedAgainst, Set<String> textFields) {
        Tag {
            checkedAgainst = checkedAgainst == null ? Set.of() : Set.copyOf(checkedAgainst);
            textFields = textFields == null ? null : Set.copyOf(textFields);
        }
    }

    static final Tag NOT_MODEL = new Tag(Level.NOT_MODEL, Set.of(), null);

    /** A model wrote all of it and nothing has judged it. Also what an unknown origin means. */
    static final Tag UNCHECKED = new Tag(Level.UNCHECKED, Set.of(), null);

    private Provenance() {}

    /** A model wrote the fields named (or all of it when {@code textFields} is null). */
    static Tag model(Set<String> textFields) {
        if (textFields != null && textFields.isEmpty()) return NOT_MODEL;
        return new Tag(Level.UNCHECKED, Set.of(), textFields);
    }

    /** A model wrote it and it passed {@code policies}. */
    static Tag checked(Collection<String> policies) {
        return new Tag(Level.CHECKED, new LinkedHashSet<>(policies), null);
    }

    /**
     * The tag of a value built from two others: the worse level, the policies both passed, and all
     * of the text unless both agree on which fields are text.
     */
    static Tag join(Tag a, Tag b) {
        if (a.level() == Level.NOT_MODEL) return b;
        if (b.level() == Level.NOT_MODEL) return a;
        Level level = a.level().compareTo(b.level()) >= 0 ? a.level() : b.level();
        Set<String> policies = new LinkedHashSet<>();
        if (level == Level.CHECKED) {
            policies.addAll(a.checkedAgainst());
            policies.retainAll(b.checkedAgainst());
        }
        return new Tag(level, policies, null);
    }

    /** The fields of an extractor's or agent's schema that hold model-written text. */
    static Set<String> textFieldsOf(SchemaDef schema) {
        Set<String> out = new LinkedHashSet<>();
        if (schema == null || schema.getFields() == null) return null;
        for (Map.Entry<String, SchemaDef> f : schema.getFields().entrySet()) {
            switch (f.getValue().getType()) {
                case NUMBER, BOOLEAN, ENUM -> {}
                default -> out.add(f.getKey());
            }
        }
        return out;
    }

    /**
     * True when a value carrying {@code tag} can leave the workflow under {@code declared}
     * policies. A value with no text in it (a number, a true/false, a record whose text fields are
     * empty) leaves freely: the policy judges what a text says.
     */
    static boolean mayLeave(Tag tag, Object value, Collection<String> declared) {
        if (tag.level() == Level.NOT_MODEL) return true;
        if (!hasText(tag, value)) return true;
        return tag.level() == Level.CHECKED && tag.checkedAgainst().containsAll(declared);
    }

    /** True when a model wrote some of the text in {@code value}, as far as its tag says. */
    static boolean modelWritten(Tag tag, Object value) {
        return tag.level() != Level.NOT_MODEL && hasText(tag, value);
    }

    /** The declared policies a value has not passed. */
    static List<String> missing(Tag tag, Collection<String> declared) {
        if (tag.level() == Level.UNCHECKED) return List.copyOf(declared);
        return declared.stream().filter(p -> !tag.checkedAgainst().contains(p)).toList();
    }

    private static boolean hasText(Tag tag, Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) return false;
        if (value instanceof CharSequence s) return !s.toString().isEmpty();
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (tag.textFields() != null && !tag.textFields().contains(String.valueOf(e.getKey())))
                    continue;
                if (hasText(new Tag(tag.level(), tag.checkedAgainst(), null), e.getValue())) return true;
            }
            return false;
        }
        if (value instanceof Iterable<?> it) {
            for (Object o : it) if (hasText(new Tag(tag.level(), tag.checkedAgainst(), null), o)) return true;
            return false;
        }
        return true;
    }
}
