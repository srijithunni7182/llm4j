package io.github.llm4j.classify;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The checks every result passes before a caller sees it (R1, R2): whichever backend produced it,
 * it is what the request asked for.
 */
final class ClassifyValidation {

    private ClassifyValidation() {}

    /**
     * Returns the result when it answers exactly the request's questions with allowed values; else
     * throws {@link ClassificationException}.
     */
    static ClassifyResult check(ClassifyRequest request, ClassifyResult result) {
        if (result == null) throw new ClassificationException("the backend returned no result");
        Set<String> asked = new LinkedHashSet<>();
        request.questions().forEach(q -> asked.add(q.name()));
        Set<String> got = result.answers().keySet();
        if (!got.equals(asked)) {
            Set<String> missing = new LinkedHashSet<>(asked);
            missing.removeAll(got);
            Set<String> extra = new LinkedHashSet<>(got);
            extra.removeAll(asked);
            throw new ClassificationException(
                    "the answers do not match the questions: missing "
                            + missing
                            + ", unexpected "
                            + extra);
        }
        for (Question q : request.questions()) {
            Answer a = result.answers().get(q.name());
            if (q instanceof Question.Choice c) {
                if (!(a instanceof ChoiceAnswer ca)) throw wrongKind(q, "a choice");
                if (!c.labels().contains(ca.label())) {
                    throw new ClassificationException(
                            "question '"
                                    + q.name()
                                    + "' was answered with '"
                                    + ca.label()
                                    + "', which is not one of "
                                    + c.labels());
                }
                for (String key : ca.probabilities().keySet()) {
                    if (!c.labels().contains(key))
                        throw new ClassificationException(
                                "question '"
                                        + q.name()
                                        + "' has a probability for unknown label '"
                                        + key
                                        + "'");
                }
            } else if (q instanceof Question.YesNo) {
                if (!(a instanceof YesNoAnswer)) throw wrongKind(q, "a yes/no");
            } else if (q instanceof Question.Score s) {
                if (!(a instanceof ScoreAnswer sa)) throw wrongKind(q, "a score");
                if (!s.scale().points().contains(sa.nearest())) {
                    throw new ClassificationException(
                            "question '"
                                    + q.name()
                                    + "' was answered with '"
                                    + sa.nearest()
                                    + "', which is not one of "
                                    + s.scale().points());
                }
                for (String key : sa.probabilities().keySet()) {
                    if (!s.scale().points().contains(key))
                        throw new ClassificationException(
                                "question '"
                                        + q.name()
                                        + "' has a probability for unknown point '"
                                        + key
                                        + "'");
                }
            }
        }
        return result;
    }

    private static ClassificationException wrongKind(Question q, String expected) {
        return new ClassificationException(
                "question '" + q.name() + "' needs " + expected + " answer");
    }
}
