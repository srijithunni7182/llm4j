package io.github.llm4j.loom.trigger;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * A standard 5-field cron expression — minute, hour, day of month, month, day of week — with
 * {@code *}, lists, ranges, steps and the names {@code JAN}–{@code DEC} / {@code SUN}–{@code SAT}
 * (0 and 7 are Sunday). As in classic cron, when both day fields are restricted a day matching either
 * one matches. Times are read in a time zone: a time skipped by a daylight-saving jump fires at the
 * moment the clock jumps to, and a repeated hour fires once.
 */
public final class CronSchedule {

    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG",
            "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    private static final int SEARCH_DAYS = 366 * 8; // leap-day schedules repeat within 8 years

    private final String expression;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;

    private CronSchedule(String expression, BitSet minutes, BitSet hours, BitSet dom, BitSet months, BitSet dow,
                         boolean domRestricted, boolean dowRestricted) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = dom;
        this.months = months;
        this.daysOfWeek = dow;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /** @throws IllegalArgumentException naming the field that is wrong */
    public static CronSchedule parse(String expression) {
        if (expression == null) throw new IllegalArgumentException("cron expression is missing");
        String[] f = expression.trim().split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("cron needs 5 fields (minute hour day-of-month month day-of-week), got "
                    + f.length + " in \"" + expression + "\"");
        }
        BitSet dow = field(f[4], "day-of-week", 0, 7, DAYS);
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        return new CronSchedule(expression.trim(),
                field(f[0], "minute", 0, 59, null),
                field(f[1], "hour", 0, 23, null),
                field(f[2], "day-of-month", 1, 31, null),
                field(f[3], "month", 1, 12, MONTHS),
                dow,
                !f[2].equals("*") && !f[2].equals("?"),
                !f[4].equals("*") && !f[4].equals("?"));
    }

    private static BitSet field(String text, String name, int min, int max, List<String> names) {
        BitSet bits = new BitSet(max + 1);
        for (String part : text.split(",")) {
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), name, 1, max, null);
            }
            int from;
            int to;
            if (range.equals("*") || range.equals("?")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] ends = range.split("-", 2);
                from = number(ends[0], name, min, max, names);
                to = number(ends[1], name, min, max, names);
                if (to < from) throw new IllegalArgumentException("cron " + name + " range " + range + " runs backwards");
            } else {
                from = number(range, name, min, max, names);
                to = slash >= 0 ? max : from;
            }
            for (int v = from; v <= to; v += step) bits.set(v);
        }
        return bits;
    }

    private static int number(String text, String name, int min, int max, List<String> names) {
        if (names != null) {
            int i = names.indexOf(text.toUpperCase(Locale.ROOT));
            if (i >= 0) return names == MONTHS ? i + 1 : i;
        }
        int v;
        try {
            v = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cron " + name + " field: '" + text + "' is not a number");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("cron " + name + " field: " + v + " is outside " + min + "-" + max);
        }
        return v;
    }

    public String expression() {
        return expression;
    }

    /** The first time strictly after {@code after} that matches, read in {@code zone}. */
    public Instant next(Instant after, ZoneId zone) {
        LocalDate day = LocalDate.ofInstant(after, zone);
        for (int d = 0; d < SEARCH_DAYS; d++, day = day.plusDays(1)) {
            if (!months.get(day.getMonthValue()) || !dayMatches(day)) continue;
            for (int h = hours.nextSetBit(0); h >= 0; h = hours.nextSetBit(h + 1)) {
                for (int m = minutes.nextSetBit(0); m >= 0; m = minutes.nextSetBit(m + 1)) {
                    LocalDateTime local = LocalDateTime.of(day, LocalTime.of(h, m));
                    Instant at = ZonedDateTime.ofLocal(local, zone, null).toInstant();
                    if (at.isAfter(after)) return at;
                }
            }
        }
        throw new IllegalArgumentException("cron \"" + expression + "\" never matches");
    }

    private boolean dayMatches(LocalDate day) {
        boolean dom = daysOfMonth.get(day.getDayOfMonth());
        boolean dow = daysOfWeek.get(day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : day.getDayOfWeek().getValue());
        if (domRestricted && dowRestricted) return dom || dow;
        return dom && dow;
    }

    @Override
    public String toString() {
        return expression;
    }
}
