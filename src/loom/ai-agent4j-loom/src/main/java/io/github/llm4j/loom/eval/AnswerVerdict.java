package io.github.llm4j.loom.eval;

import io.github.llm4j.eval.dataset.EvalScenario;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a case's answer set says about an answer: {@code exact} (it holds the correct conclusion),
 * {@code acceptable} (it holds an accepted equivalent), {@code rejected} (it holds a rejected
 * answer) or {@code unmatched} (none of these). Entries are short key phrases the answer must
 * contain; capitalisation, punctuation and spacing are ignored. The order is the guide's: exact,
 * then acceptable, then rejected. An answer that matches nothing is not wrong by itself; it may be
 * correct and unanticipated, so it goes to a person and, if right, into the equivalents.
 *
 * <p>Embedding similarity and an LLM comparison, the weaker matchers the guide lists, are not built;
 * if a threshold would be needed to make grading work, add equivalents instead.
 *
 * @param kind the verdict
 * @param matched the entry that matched, null for {@code unmatched}
 */
public record AnswerVerdict(Kind kind, String matched) {

    /** The four verdicts. */
    public enum Kind {
        EXACT,
        ACCEPTABLE,
        REJECTED,
        UNMATCHED;

        /** The word the report uses. */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Whether a case states an answer set to compare with. */
    public static boolean applies(EvalScenario s) {
        return s.correctConclusion() != null
                || !s.equivalents().isEmpty()
                || !s.rejectedAnswers().isEmpty();
    }

    /** The verdict for an answer. */
    public static AnswerVerdict of(EvalScenario s, String answer) {
        String text = normal(answer);
        if (s.correctConclusion() != null && holds(text, s.correctConclusion()))
            return new AnswerVerdict(Kind.EXACT, s.correctConclusion());
        String equivalent = first(text, s.equivalents());
        if (equivalent != null) return new AnswerVerdict(Kind.ACCEPTABLE, equivalent);
        String rejected = first(text, s.rejectedAnswers());
        if (rejected != null) return new AnswerVerdict(Kind.REJECTED, rejected);
        return new AnswerVerdict(Kind.UNMATCHED, null);
    }

    private static String first(String text, List<String> entries) {
        for (String e : entries) if (holds(text, e)) return e;
        return null;
    }

    private static boolean holds(String text, String entry) {
        String phrase = normal(entry);
        return !phrase.isEmpty() && text.contains(phrase);
    }

    static String normal(String s) {
        return s == null ? "" : NOT_A_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
