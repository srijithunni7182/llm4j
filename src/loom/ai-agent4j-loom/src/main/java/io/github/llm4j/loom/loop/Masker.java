package io.github.llm4j.loom.loop;

import io.github.llm4j.loom.review.fact.SecretShapes;
import io.github.llm4j.privacy.PIIDetectionResult;
import io.github.llm4j.privacy.PIIEntity;
import io.github.llm4j.privacy.RegexPIIDetector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes personal data and secrets out of text before it is shown or written. Secrets are replaced
 * by {@code ‹secret›}. Personal data is replaced by a stand-in of the same shape, so a condition
 * that looks at the shape of a value (an email, a number) still takes the same way: an email stays
 * an email, a number stays a number of the same length. The same value gets the same stand-in for
 * the life of one masker, so a case still says "the same customer". Literal terms (names the
 * pattern cannot find) are replaced by {@code ‹term1›}.
 *
 * <p>This is a safeguard, not a guarantee: it finds what patterns and the given terms can find.
 */
public final class Masker {

    private record Span(int start, int end, String type, String value) {}

    /** A phone number written with spaces, which the shared detector does not take. */
    private static final Pattern US_PHONE =
            Pattern.compile(
                    "(?<![\\d])(?:\\+?1[\\s.-])?\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}(?![\\d])");

    private final RegexPIIDetector detector = new RegexPIIDetector();
    private final List<String> terms;
    private final Map<String, String> standIns = new LinkedHashMap<>();
    private final Set<String> kinds = new LinkedHashSet<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    public Masker(Collection<String> terms) {
        this.terms =
                terms == null
                        ? List.of()
                        : terms.stream()
                                .filter(t -> t != null && !t.isBlank())
                                .sorted(Comparator.comparingInt(String::length).reversed())
                                .toList();
    }

    public Masker() {
        this(List.of());
    }

    /** What kinds of value were replaced so far, for the case to say (never the values). */
    public Set<String> kinds() {
        return Set.copyOf(kinds);
    }

    /** The text with secrets, personal data and the given terms replaced. */
    public String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = SecretShapes.scrub(text);
        if (!out.equals(text)) kinds.add("secret");
        List<Span> spans = new ArrayList<>();
        PIIDetectionResult found = detector.detect(out);
        if (found.containsPII())
            for (PIIEntity e : found.getEntities())
                spans.add(
                        new Span(
                                e.getStartIndex(),
                                e.getEndIndex(),
                                e.getType().name(),
                                e.getValue()));
        Matcher us = US_PHONE.matcher(out);
        while (us.find()) spans.add(new Span(us.start(), us.end(), "PHONE", us.group()));
        // the longest span wins where two overlap
        spans.sort(
                Comparator.comparingInt((Span x) -> x.end - x.start)
                        .reversed()
                        .thenComparingInt(x -> x.start));
        List<Span> kept = new ArrayList<>();
        for (Span sp : spans)
            if (kept.stream().noneMatch(k -> sp.start < k.end && k.start < sp.end)) kept.add(sp);
        kept.sort(Comparator.comparingInt((Span x) -> x.start).reversed());
        if (!kept.isEmpty()) {
            StringBuilder sb = new StringBuilder(out);
            // numbered in reading order, so the stand-ins do not depend on how they were found
            List<Span> reading = new ArrayList<>(kept);
            reading.sort(Comparator.comparingInt(x -> x.start));
            Map<Span, String> stand = new java.util.IdentityHashMap<>();
            for (Span sp : reading) stand.put(sp, standIn(sp.type, sp.value));
            for (Span sp : kept) {
                sb.replace(sp.start, sp.end, stand.get(sp));
                kinds.add(sp.type.toLowerCase(Locale.ROOT));
            }
            out = sb.toString();
        }
        int n = 0;
        for (String term : terms) {
            n++;
            Matcher m = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE).matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement("‹term" + n + "›"));
                kinds.add("term");
            }
        }
        return out;
    }

    /** As {@link #mask(String)} for every text inside a map, list or text. */
    @SuppressWarnings("unchecked")
    public Object maskValue(Object value) {
        if (value instanceof String s) return mask(s);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, v) -> out.put(String.valueOf(k), maskValue(v)));
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) out.add(maskValue(o));
            return out;
        }
        return value;
    }

    private String standIn(String type, String value) {
        String key = type + ":" + (type.equals("EMAIL") ? value.toLowerCase(Locale.ROOT) : value);
        return standIns.computeIfAbsent(key, k -> make(type, value));
    }

    private String make(String type, String value) {
        int n = counters.merge(type, 1, Integer::sum);
        return switch (type) {
            case "EMAIL" -> "person" + n + "@example.invalid";
            case "URL" -> "https://masked.invalid/u" + n;
            default -> sameShape(value, n);
        };
    }

    /** Digits become zeros (the last ones the counter), everything else stays: same length. */
    private static String sameShape(String value, int n) {
        char[] out = value.toCharArray();
        List<Integer> digits = new ArrayList<>();
        for (int i = 0; i < out.length; i++) if (Character.isDigit(out[i])) digits.add(i);
        for (int i : digits) out[i] = '0';
        String counter = Integer.toString(n);
        for (int k = 0; k < counter.length() && k < digits.size(); k++)
            out[digits.get(digits.size() - 1 - k)] = counter.charAt(counter.length() - 1 - k);
        // a card keeps its first digit (the brand), so a rule that reads the brand still holds
        if (digits.size() >= 13
                && !digits.isEmpty()
                && Character.isDigit(value.charAt(digits.get(0))))
            out[digits.get(0)] = value.charAt(digits.get(0));
        return new String(out);
    }
}
