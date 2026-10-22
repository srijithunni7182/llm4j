package io.github.llm4j.loom.inspect.map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What can be measured from the text of a run while the text is still in the process, so the
 * console can raise an incident without ever being sent the text: a short hash of a tool call, a
 * short hash of an answer, and how many of the figures an answer states appear in nothing the step
 * was given or fetched.
 *
 * <p>The figure check is a heuristic and says only "no source found", never "false": a number that
 * was computed, rounded or said in words will count as ungrounded. That is why the incident built
 * on it is labelled suspect.
 */
public final class Signals {

    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(%?)");
    private static final int CORPUS_LIMIT = 400_000;
    private static final int EXAMPLES = 5;

    private Signals() {}

    /**
     * Twelve hex characters of SHA-256: enough to tell two things apart, not enough to read
     * anything back.
     */
    public static String hash(String text) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A tool call: the tool and its input, with whitespace made uniform so a re-spaced call is the
     * same call.
     */
    public static String actionHash(String tool, String input) {
        return hash(tool + "\u0000" + squash(input));
    }

    /** An answer, with whitespace made uniform. */
    public static String outputHash(String answer) {
        return hash(squash(answer));
    }

    private static String squash(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    /**
     * What a step was given and fetched: the task, each tool input, each tool result. Bounded, so a
     * huge page cannot grow it without limit.
     */
    public static final class Corpus {
        private final StringBuilder text = new StringBuilder();

        public void add(String more) {
            if (more == null || text.length() >= CORPUS_LIMIT) return;
            text.append(more, 0, Math.min(more.length(), CORPUS_LIMIT - text.length()))
                    .append('\n');
        }

        Set<String> figures() {
            return figuresIn(text.toString());
        }
    }

    /** The result of checking an answer against a corpus. */
    public record Grounding(int figures, int ungrounded, List<String> examples) {}

    /**
     * How many distinct figures {@code answer} states and how many of them appear nowhere in {@code
     * corpus}.
     */
    public static Grounding ground(String answer, Corpus corpus) {
        Set<String> stated = figuresIn(answer);
        if (stated.isEmpty()) return new Grounding(0, 0, List.of());
        Set<String> known = corpus.figures();
        List<String> missing = new ArrayList<>();
        for (String f : stated) if (!known.contains(f)) missing.add(f);
        return new Grounding(
                stated.size(),
                missing.size(),
                missing.subList(0, Math.min(EXAMPLES, missing.size())));
    }

    /**
     * The figures in a text, as comparable strings: commas dropped, a percent sign kept. A bare
     * one-digit number is not a figure (it is "step 2", "3 findings"); anything longer, with a
     * decimal point, a thousands comma or a percent sign is.
     */
    static Set<String> figuresIn(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            boolean figure = digits.length() >= 2 || m.group(2).equals("%") || digits.contains(".");
            if (!figure) continue;
            out.add(digits + m.group(2));
        }
        return out;
    }
}
