package io.github.llm4j.loom.ladder;

import io.github.llm4j.loom.autonomy.expr.V;

/** Values and expressions as short text for people: the status of a ladder, the reason a level changed. */
final class Show {

    private Show() { }

    static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        String s = String.format(java.util.Locale.ROOT, "%.4f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static String duration(long ms) {
        long day = 86_400_000L, hour = 3_600_000L, minute = 60_000L, second = 1000L;
        if (ms != 0 && ms % day == 0) return ms / day + "d";
        if (ms != 0 && ms % hour == 0) return ms / hour + "h";
        if (ms != 0 && ms % minute == 0) return ms / minute + "m";
        if (ms != 0 && ms % second == 0) return ms / second + "s";
        return ms + "ms";
    }

    static String value(V v) {
        if (v instanceof V.Undefined) return "unknown";
        if (v instanceof V.Bool b) return String.valueOf(b.value());
        if (v instanceof V.Num n) return number(n.value());
        if (v instanceof V.Str s) return "\"" + s.value() + "\"";
        if (v instanceof V.Dur d) return duration(d.millis());
        if (v instanceof V.Rat r) return number(r.value()) + " (" + r.n() + " cases, lower bound " + number(r.lower95()) + ")";
        if (v instanceof V.Lst l) return l.items().size() + " values";
        if (v instanceof V.Cs c) return c.cases().size() + " cases";
        return v.getClass().getSimpleName();
    }

    /** One pair of parentheses around the whole text removed: {@code (a >= 1)} becomes {@code a >= 1}. */
    static String unwrapped(String s) {
        if (s.length() < 2 || s.charAt(0) != '(' || s.charAt(s.length() - 1) != ')') return s;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && i < s.length() - 1) return s;
        }
        return s.substring(1, s.length() - 1);
    }
}
