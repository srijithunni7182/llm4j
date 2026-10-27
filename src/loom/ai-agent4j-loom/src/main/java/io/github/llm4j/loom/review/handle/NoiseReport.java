package io.github.llm4j.loom.review.handle;

import io.github.llm4j.loom.review.engine.ReviewReport;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Note;
import io.github.llm4j.loom.review.model.Suppressed;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which rules are noisy in practice: per rule, how many findings are open, accepted, baselined or
 * expired, and which rules are accepted in more than a share of the places they fire (default 50
 * %), with the suggestion to tune or turn them off. It reads only what the review produced.
 */
public final class NoiseReport {

    public static final int DEFAULT_SHARE = 50;

    private NoiseReport() {}

    private static final class Row {
        int open;
        int accepted;
        int baselined;
        int expired;
    }

    public static String write(ReviewReport r, int sharePercent) {
        Map<String, Row> rows = new TreeMap<>();
        for (Finding f : r.findings()) rows.computeIfAbsent(f.rule(), k -> new Row()).open++;
        for (Suppressed s : r.suppressed())
            rows.computeIfAbsent(s.finding().rule(), k -> new Row()).accepted++;
        for (Finding f : r.baselined()) rows.computeIfAbsent(f.rule(), k -> new Row()).baselined++;
        for (Note n : r.notes())
            if (n.rule() != null && n.message().contains(" expired on "))
                rows.computeIfAbsent(n.rule(), k -> new Row()).expired++;
        StringBuilder b = new StringBuilder();
        b.append(
                String.format(
                        Locale.ROOT,
                        "%-8s %6s %9s %10s %8s%n",
                        "rule",
                        "open",
                        "accepted",
                        "baselined",
                        "expired"));
        StringBuilder noisy = new StringBuilder();
        for (var e : rows.entrySet()) {
            Row x = e.getValue();
            b.append(
                    String.format(
                            Locale.ROOT,
                            "%-8s %6d %9d %10d %8d%n",
                            e.getKey(),
                            x.open,
                            x.accepted,
                            x.baselined,
                            x.expired));
            int places = x.open + x.accepted + x.expired;
            if (places >= 2 && x.accepted * 100 >= (long) sharePercent * places + 1)
                noisy.append("  ")
                        .append(e.getKey())
                        .append(" is accepted in ")
                        .append(x.accepted)
                        .append(" of ")
                        .append(places)
                        .append(" places: tune it (e.g. ")
                        .append(e.getKey())
                        .append(".severity = low")
                        .append(") or turn it off in review.properties (")
                        .append(e.getKey())
                        .append(" = off)\n");
        }
        if (rows.isEmpty())
            b.append("no findings, accepted entries or baseline entries to report\n");
        if (noisy.length() > 0)
            b.append("\nNoisy in practice (accepted in more than ")
                    .append(sharePercent)
                    .append(" % of the places it fires):\n")
                    .append(noisy);
        b.append(
                "\nThis report is computed from this project's files only; nothing is sent anywhere.\n");
        return b.toString();
    }
}
