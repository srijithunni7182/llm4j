package io.github.llm4j.loom.hints;

import io.github.llm4j.loom.lexer.Token;
import io.github.llm4j.loom.lexer.TokenType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The mistakes that have a known fix: a failure that said X when it meant Y is answered with one
 * sentence that names the fix. Adding one is a row in the list and a test. A hint names the words
 * of the language and their order, never the text of a prompt, a key or a value.
 */
public final class KnownMistakes {

    /** What a mistake is matched against: the failure message and, for a parse error, where it was. */
    public record Context(String message, List<Token> tokens, int index) {}

    /** One known mistake: when it applies, and the sentence that fixes it. */
    public record Mistake(String id, Predicate<Context> matches, String hint) {}

    private static final List<Mistake> ALL =
            List.of(
                    new Mistake(
                            "expecting-after-retry",
                            KnownMistakes::expectingAfterRetry,
                            "`expecting {…}` must come before `retry N` on a `delegate`; write `delegate \"…\" to A -> x expecting {…} retry 1`."),
                    new Mistake(
                            "reserved-value-key",
                            c ->
                                    c.message() != null
                                            && c.message().contains("\"value\" is reserved"),
                            "`value` is reserved in a scripted result; write the result as a plain string, or use another key such as `answer`."));

    private KnownMistakes() {}

    /** Every known mistake, in the order they are tried. */
    public static List<Mistake> all() {
        return ALL;
    }

    /** The hint for the first mistake that applies, if any. */
    public static Optional<String> hintFor(Context context) {
        Objects.requireNonNull(context, "context");
        return ALL.stream().filter(m -> m.matches().test(context)).map(Mistake::hint).findFirst();
    }

    /** The message with its hint after it, or the message as it was when no mistake applies. */
    public static String withHint(String message, List<Token> tokens, int index) {
        Objects.requireNonNull(message, "message");
        return hintFor(new Context(message, tokens, index))
                .map(h -> message + " Hint: " + h)
                .orElse(message);
    }

    /** {@code retry N} earlier on the line, then the word {@code expecting} where a statement should start. */
    private static boolean expectingAfterRetry(Context c) {
        if (c.tokens() == null || c.index() < 0 || c.index() >= c.tokens().size()) return false;
        Token at = c.tokens().get(c.index());
        if (!"expecting".equals(at.getValue())) return false;
        for (int i = c.index() - 1; i >= 0; i--) {
            Token before = c.tokens().get(i);
            if (before.getLine() != at.getLine()) return false;
            if (before.getType() == TokenType.RETRY) return true;
        }
        return false;
    }
}
