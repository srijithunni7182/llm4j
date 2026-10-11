package io.github.llm4j.loom.autonomy.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code distance(a, b)}: how far a person moved a proposal, from 0 (untouched) to 1. Text is the edit distance over the longer length;
 * structured values are the share of leaf fields that differ; numbers and booleans are 0 or 1. A pair of different kinds, a missing value, or
 * two large documents with little in common (too costly to measure) give {@link V#UNDEFINED}, so an unmeasurable edit never counts as small.
 */
public final class Distance {

    /** The most character comparisons spent on one pair of texts. */
    static final long BUDGET = 40_000_000L;

    private Distance() { }

    public static V of(V a, V b) {
        if (a instanceof V.Str x && b instanceof V.Str y) return text(x.value(), y.value());
        if (a instanceof V.Num x && b instanceof V.Num y) return V.num(Double.compare(x.value(), y.value()) == 0 ? 0 : 1);
        if (a instanceof V.Bool x && b instanceof V.Bool y) return V.num(x.value() == y.value() ? 0 : 1);
        if (a instanceof V.Rec x && b instanceof V.Rec y) return structured(x, y);
        return V.UNDEFINED;
    }

    private static V text(String a, String b) {
        if (a.equals(b)) return V.num(0);
        int[] x = a.codePoints().toArray();
        int[] y = b.codePoints().toArray();
        int max = Math.max(x.length, y.length);
        int prefix = 0;
        while (prefix < x.length && prefix < y.length && x[prefix] == y[prefix]) prefix++;
        int suffix = 0;
        while (suffix < x.length - prefix && suffix < y.length - prefix && x[x.length - 1 - suffix] == y[y.length - 1 - suffix]) suffix++;
        int[] mx = slice(x, prefix, x.length - suffix);
        int[] my = slice(y, prefix, y.length - suffix);
        if (mx.length == 0 || my.length == 0) return V.num((double) Math.max(mx.length, my.length) / max);
        if ((long) mx.length * my.length > BUDGET) return V.UNDEFINED;
        return V.num((double) levenshtein(mx, my) / max);
    }

    private static int[] slice(int[] a, int from, int to) {
        int[] out = new int[Math.max(0, to - from)];
        System.arraycopy(a, from, out, 0, out.length);
        return out;
    }

    private static int levenshtein(int[] a, int[] b) {
        int[] prev = new int[b.length + 1];
        int[] cur = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prev[j] = j;
        for (int i = 1; i <= a.length; i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length];
    }

    private static V structured(V.Rec a, V.Rec b) {
        Map<String, V> fa = new LinkedHashMap<>();
        Map<String, V> fb = new LinkedHashMap<>();
        leaves("", a, fa);
        leaves("", b, fb);
        List<String> keys = new ArrayList<>(fa.keySet());
        for (String k : fb.keySet()) if (!fa.containsKey(k)) keys.add(k);
        if (keys.isEmpty()) return V.num(0);
        int differ = 0;
        for (String k : keys) if (!fa.containsKey(k) || !fb.containsKey(k) || !fa.get(k).equals(fb.get(k))) differ++;
        return V.num((double) differ / keys.size());
    }

    private static void leaves(String path, V v, Map<String, V> out) {
        if (v instanceof V.Rec r) {
            for (Map.Entry<String, V> e : r.fields().entrySet()) leaves(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), out);
        } else {
            out.put(path, v);
        }
    }
}
