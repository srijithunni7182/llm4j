package io.github.llm4j.loom.inspect.rules;

import io.github.llm4j.loom.inspect.state.InspectRules;
import io.github.llm4j.loom.inspect.state.Thresholds;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The console's rules for what is trouble, kept <b>beside</b> a workflow and not inside it: {@code
 * flow.inspect} next to {@code flow.loom}, or any file given with {@code --observe-rules}. The Loom
 * script says what the workflow does; this file says how it is watched, so the script stays as
 * readable as it was, and the same script can be watched differently in test and in production.
 *
 * <pre>
 * # flow.inspect: what is normal for each agent. A rule left out keeps the console's default.
 * Editor {
 *     same_action: 4 times                   # the same tool call, rounds of a loop counted together
 *     no_progress: 3 rounds on review.score  # this workflow variable unchanged at the end of 3 loop rounds in a row
 * }
 * Scriptwriter { ungrounded_figures: warn at 2, high at 4 }
 * *            { paused_longer_than: 90s }   # for every agent that does not say otherwise
 * </pre>
 *
 * Rules: {@code same_action N times}, {@code same_output N times}, {@code tool_failures N times},
 * {@code no_progress N rounds on <variable>}, {@code ungrounded_figures warn at N, high at M},
 * {@code paused_longer_than <30s|5m|2h>}. One per line, or several on one line separated by {@code
 * ;}. {@code #} and {@code //} start a comment. It changes nothing about how a run behaves.
 */
public final class RulesFile {

    /** The name a block uses for "every agent". */
    public static final String ALL = "*";

    public static final List<String> RULES =
            List.of(
                    "same_action",
                    "same_output",
                    "tool_failures",
                    "no_progress",
                    "ungrounded_figures",
                    "paused_longer_than",
                    "context_nearly_full",
                    "truncated_answers",
                    "compaction_thrash",
                    "tool_success_rate",
                    "latency_spike",
                    "access_denied",
                    "injection_scan",
                    "trifecta");

    /** Something wrong with the file or with how it meets the script. */
    public record Problem(int line, boolean error, String message) {
        @Override
        public String toString() {
            return (error ? "error" : "warning")
                    + (line > 0 ? " line " + line : "")
                    + ": "
                    + message;
        }
    }

    /**
     * What was read: the rules by agent name (the key {@code *} is "every agent") and the lines
     * they came from.
     */
    public record Parsed(
            Map<String, InspectRules> rules, Map<String, Integer> lines, List<Problem> problems) {
        public boolean ok() {
            return problems.stream().noneMatch(Problem::error);
        }
    }

    private static final Pattern COUNT =
            Pattern.compile("(same_action|same_output|tool_failures)\\s*:\\s*(\\d+)\\s+times");
    private static final Pattern PROGRESS =
            Pattern.compile(
                    "no_progress\\s*:\\s*(\\d+)\\s+rounds\\s+on\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern UNGROUNDED =
            Pattern.compile(
                    "ungrounded_figures\\s*:\\s*(warn|high)\\s+at\\s+(\\d+)(?:\\s*,\\s*(warn|high)\\s+at\\s+(\\d+))?");
    private static final Pattern PAUSED =
            Pattern.compile("paused_longer_than\\s*:\\s*(\\d+)\\s*([smhd])");
    private static final Pattern CONTEXT =
            Pattern.compile(
                    "context_nearly_full\\s*:\\s*(warn|high)\\s+at\\s+(\\d+)\\s*%(?:\\s*,\\s*(warn|high)\\s+at\\s+(\\d+)\\s*%)?");
    private static final Pattern TRUNCATED =
            Pattern.compile("truncated_answers\\s*:\\s*(\\d+)\\s+times");
    private static final Pattern THRASH =
            Pattern.compile("compaction_thrash\\s*:\\s*(\\d+)\\s+in\\s+(\\d+)\\s+calls");
    private static final Pattern TOOL_RATE =
            Pattern.compile(
                    "tool_success_rate\\s*:\\s*below\\s+(\\d+)\\s*%\\s+over\\s+(\\d+)\\s+calls");
    private static final Pattern SPIKE =
            Pattern.compile("latency_spike\\s*:\\s*(\\d+(?:\\.\\d+)?)\\s*x\\s+p50");
    private static final Pattern ACCESS =
            Pattern.compile("access_denied\\s*:\\s*(\\d+)\\s+in\\s+(\\d+)\\s*([smh])");
    private static final Pattern INJECTION = Pattern.compile("injection_scan\\s*:\\s*(on|off)");
    private static final Pattern TRIFECTA =
            Pattern.compile("trifecta\\s*:\\s*accepted\\s+\"([^\"]*)\"");
    private static final Pattern OPEN =
            Pattern.compile("^(\\*|[A-Za-z_][A-Za-z0-9_]*)\\s*\\{(.*)$");

    private RulesFile() {}

    /**
     * The line without its {@code #} or {@code //} comment; a comment mark inside a quoted reason
     * is part of the reason.
     */
    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted
                    && (c == '#'
                            || (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')))
                return line.substring(0, i);
        }
        return line;
    }

    /**
     * The rules on one line, separated by {@code ;} (a {@code ;} inside a quoted reason does not
     * separate).
     */
    private static List<String> splitRules(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            if (c == ';' && !quoted) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    /** {@code flow.loom} to {@code flow.inspect}, in the same folder. */
    public static Path sidecarFor(Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return script.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".inspect");
    }

    public static Parsed read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Parsed parse(String text) {
        List<Problem> problems = new ArrayList<>();
        Map<String, Builder> blocks = new LinkedHashMap<>();
        Map<String, Integer> lines = new LinkedHashMap<>();
        Builder current = null;
        String name = null;
        String[] all = text.split("\\R", -1);
        for (int i = 0; i < all.length; i++) {
            int no = i + 1;
            String line = stripComment(all[i]).strip();
            if (line.isEmpty()) continue;
            if (current == null) {
                Matcher m = OPEN.matcher(line);
                if (!m.matches()) {
                    problems.add(
                            new Problem(
                                    no,
                                    true,
                                    "expected 'AgentName {' (or '* {' for every agent), got '"
                                            + line
                                            + "'"));
                    continue;
                }
                name = m.group(1);
                if (blocks.containsKey(name))
                    problems.add(new Problem(no, true, "there is already a block for " + name));
                current = new Builder();
                blocks.put(name, current);
                lines.put(name, no);
                line = m.group(2).strip();
                if (line.isEmpty()) continue;
            }
            boolean closes = line.endsWith("}");
            if (closes) line = line.substring(0, line.length() - 1).strip();
            for (String part : splitRules(line)) {
                String rule = part.strip();
                if (!rule.isEmpty()) apply(rule, no, current, name, problems);
            }
            if (closes) current = null;
        }
        if (current != null)
            problems.add(
                    new Problem(
                            all.length, true, "the block for " + name + " is not closed with '}'"));
        Map<String, InspectRules> out = new LinkedHashMap<>();
        for (Map.Entry<String, Builder> b : blocks.entrySet()) {
            Builder v = b.getValue();
            if (v.empty()) {
                problems.add(
                        new Problem(
                                lines.get(b.getKey()),
                                false,
                                "the block for "
                                        + b.getKey()
                                        + " sets no rule, so it does nothing"));
                continue;
            }
            if (v.warn != null && v.high != null && v.warn >= v.high) {
                problems.add(
                        new Problem(
                                lines.get(b.getKey()),
                                true,
                                b.getKey()
                                        + ": ungrounded_figures warn at "
                                        + v.warn
                                        + " must be lower than high at "
                                        + v.high));
            }
            if (v.ctxWarn != null && v.ctxHigh != null && v.ctxWarn >= v.ctxHigh) {
                problems.add(
                        new Problem(
                                lines.get(b.getKey()),
                                true,
                                b.getKey()
                                        + ": context_nearly_full warn at "
                                        + Math.round(v.ctxWarn * 100)
                                        + "% must be lower than high at "
                                        + Math.round(v.ctxHigh * 100)
                                        + "%"));
            }
            if (ALL.equals(b.getKey()) && v.progressVar != null) {
                problems.add(
                        new Problem(
                                lines.get(b.getKey()),
                                true,
                                "no_progress names a variable that belongs to one agent's loop: put it in that agent's block, not in *"));
            }
            if (ALL.equals(b.getKey()) && v.trifectaAccepted != null) {
                problems.add(
                        new Problem(
                                lines.get(b.getKey()),
                                false,
                                "* accepts the lethal trifecta for every agent that does not say otherwise: that hides the signal where it matters most. Accept it for the agents you have reviewed, by name."));
            }
            out.put(b.getKey(), v.build());
        }
        return new Parsed(out, lines, problems);
    }

    /**
     * The file for these rules, as {@link #parse} reads it back: {@code *} first, then agents by
     * name; one rule per line. Comments are not kept (a file written from the page's editor is
     * generated), so what a person wrote by hand should be kept by editing it as text.
     */
    public static String write(Map<String, InspectRules> rules) {
        StringBuilder out =
                new StringBuilder(
                        "# Inspect rules, kept on the server. Laid over each run's own rules, rule by rule.\n");
        List<String> names = new ArrayList<>(rules.keySet());
        java.util.Collections.sort(names);
        if (names.remove(ALL)) names.add(0, ALL);
        for (String name : names) {
            InspectRules r = rules.get(name);
            List<String> lines = new ArrayList<>();
            if (r.sameAction() != null) lines.add("same_action: " + r.sameAction() + " times");
            if (r.sameOutput() != null) lines.add("same_output: " + r.sameOutput() + " times");
            if (r.toolFailures() != null)
                lines.add("tool_failures: " + r.toolFailures() + " times");
            if (r.ungroundedWarn() != null || r.ungroundedHigh() != null) {
                String w = r.ungroundedWarn() == null ? null : "warn at " + r.ungroundedWarn();
                String h = r.ungroundedHigh() == null ? null : "high at " + r.ungroundedHigh();
                lines.add("ungrounded_figures: " + (w == null ? h : h == null ? w : w + ", " + h));
            }
            if (r.pausedLongerThanSeconds() != null)
                lines.add("paused_longer_than: " + span(r.pausedLongerThanSeconds()));
            if (r.noProgressRounds() != null && r.noProgressVariable() != null)
                lines.add(
                        "no_progress: "
                                + r.noProgressRounds()
                                + " rounds on "
                                + r.noProgressVariable());
            InspectRules.Telemetry t = r.telemetry();
            if (t != null) {
                if (t.contextWarn() != null || t.contextHigh() != null) {
                    String w = t.contextWarn() == null ? null : "warn at " + pct(t.contextWarn());
                    String h = t.contextHigh() == null ? null : "high at " + pct(t.contextHigh());
                    lines.add(
                            "context_nearly_full: "
                                    + (w == null ? h : h == null ? w : w + ", " + h));
                }
                if (t.truncatedAnswers() != null)
                    lines.add("truncated_answers: " + t.truncatedAnswers() + " times");
                if (t.thrashCompactions() != null && t.thrashOfCalls() != null)
                    lines.add(
                            "compaction_thrash: "
                                    + t.thrashCompactions()
                                    + " in "
                                    + t.thrashOfCalls()
                                    + " calls");
                if (t.toolRate() != null && t.toolRateWindow() != null)
                    lines.add(
                            "tool_success_rate: below "
                                    + pct(t.toolRate())
                                    + " over "
                                    + t.toolRateWindow()
                                    + " calls");
                if (t.spikeFactor() != null)
                    lines.add(
                            "latency_spike: "
                                    + (t.spikeFactor() == Math.rint(t.spikeFactor())
                                            ? String.valueOf(t.spikeFactor().longValue())
                                            : String.valueOf(t.spikeFactor()))
                                    + "x p50");
            }
            InspectRules.Security sec = r.security();
            if (sec != null) {
                if (sec.accessDeniedCount() != null && sec.accessDeniedSeconds() != null)
                    lines.add(
                            "access_denied: "
                                    + sec.accessDeniedCount()
                                    + " in "
                                    + span(sec.accessDeniedSeconds()));
                if (sec.injectionScan() != null)
                    lines.add("injection_scan: " + (sec.injectionScan() ? "on" : "off"));
                if (sec.trifectaAccepted() != null)
                    lines.add("trifecta: accepted \"" + sec.trifectaAccepted() + "\"");
            }
            if (lines.isEmpty()) continue;
            out.append('\n').append(name).append(" {\n");
            for (String l : lines) out.append("    ").append(l).append('\n');
            out.append("}\n");
        }
        return out.toString();
    }

    private static String pct(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /** Seconds as the largest whole unit: {@code 90s}, {@code 5m}, {@code 2h}, {@code 1d}. */
    static String span(long seconds) {
        if (seconds % 86400 == 0) return (seconds / 86400) + "d";
        if (seconds % 3600 == 0) return (seconds / 3600) + "h";
        if (seconds % 60 == 0) return (seconds / 60) + "m";
        return seconds + "s";
    }

    private static final class Builder {
        Integer sameAction;
        Integer sameOutput;
        Integer toolFailures;
        Integer warn;
        Integer high;
        Long paused;
        Integer rounds;
        String progressVar;
        Double ctxWarn;
        Double ctxHigh;
        Integer truncated;
        Integer thrashN;
        Integer thrashOf;
        Double toolRate;
        Integer toolWindow;
        Double spike;
        Integer accessCount;
        Long accessSeconds;
        Boolean injectionScan;
        String trifectaAccepted;

        InspectRules.Security security() {
            return new InspectRules.Security(
                    accessCount, accessSeconds, injectionScan, trifectaAccepted);
        }

        boolean empty() {
            return sameAction == null
                    && sameOutput == null
                    && toolFailures == null
                    && warn == null
                    && high == null
                    && paused == null
                    && rounds == null
                    && telemetry().isEmpty()
                    && security().isEmpty();
        }

        InspectRules.Telemetry telemetry() {
            return new InspectRules.Telemetry(
                    ctxWarn, ctxHigh, truncated, thrashN, thrashOf, toolRate, toolWindow, spike);
        }

        InspectRules build() {
            InspectRules.Telemetry t = telemetry();
            return new InspectRules(
                    sameAction,
                    sameOutput,
                    toolFailures,
                    warn,
                    high,
                    paused,
                    rounds,
                    progressVar,
                    t.isEmpty() ? null : t,
                    security().isEmpty() ? null : security());
        }
    }

    private static void apply(
            String rule, int line, Builder b, String block, List<Problem> problems) {
        Matcher m;
        if ((m = COUNT.matcher(rule)).matches()) {
            Integer n = whole(m.group(2), rule, line, problems);
            if (n == null) return;
            switch (m.group(1)) {
                case "same_action" -> b.sameAction = n;
                case "same_output" -> b.sameOutput = n;
                default -> b.toolFailures = n;
            }
        } else if ((m = PROGRESS.matcher(rule)).matches()) {
            Integer n = whole(m.group(1), rule, line, problems);
            if (n == null) return;
            b.rounds = n;
            b.progressVar = m.group(2);
        } else if ((m = UNGROUNDED.matcher(rule)).matches()) {
            setLevel(b, m.group(1), whole(m.group(2), rule, line, problems), line, problems);
            if (m.group(3) != null)
                setLevel(b, m.group(3), whole(m.group(4), rule, line, problems), line, problems);
        } else if ((m = PAUSED.matcher(rule)).matches()) {
            long n = Long.parseLong(m.group(1));
            if (n < 1) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "paused_longer_than must be positive, got '" + rule + "'"));
                return;
            }
            b.paused =
                    n
                            * switch (m.group(2)) {
                                case "s" -> 1L;
                                case "m" -> 60L;
                                case "h" -> 3600L;
                                default -> 86400L;
                            };
        } else if ((m = CONTEXT.matcher(rule)).matches()) {
            percent(b, m.group(1), m.group(2), rule, line, problems, 1, 100);
            if (m.group(3) != null)
                percent(b, m.group(3), m.group(4), rule, line, problems, 1, 100);
        } else if ((m = TRUNCATED.matcher(rule)).matches()) {
            Integer n = whole(m.group(1), rule, line, problems);
            if (n != null) b.truncated = n;
        } else if ((m = THRASH.matcher(rule)).matches()) {
            Integer n = whole(m.group(1), rule, line, problems);
            Integer of = whole(m.group(2), rule, line, problems);
            if (n == null || of == null) return;
            if (n > of) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'"
                                        + rule
                                        + "': there cannot be more compactions than calls. Write it as compaction_thrash: 4 in 10 calls"));
                return;
            }
            b.thrashN = n;
            b.thrashOf = of;
        } else if ((m = TOOL_RATE.matcher(rule)).matches()) {
            long pct = Long.parseLong(m.group(1));
            Integer window = whole(m.group(2), rule, line, problems);
            if (pct < 1 || pct > 99) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'" + rule + "': the success rate must be between 1% and 99%"));
                return;
            }
            if (window == null) return;
            if (window < Thresholds.MIN_RATE_CALLS) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'"
                                        + rule
                                        + "': a rate needs at least "
                                        + Thresholds.MIN_RATE_CALLS
                                        + " calls to mean anything"));
                return;
            }
            b.toolRate = pct / 100.0;
            b.toolWindow = window;
        } else if ((m = SPIKE.matcher(rule)).matches()) {
            double factor = Double.parseDouble(m.group(1));
            if (factor <= 1.0 || factor > 1000) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'"
                                        + rule
                                        + "': a spike is a call slower than more than 1 times the usual (and at most 1000)"));
                return;
            }
            b.spike = factor;
        } else if ((m = ACCESS.matcher(rule)).matches()) {
            Integer n = whole(m.group(1), rule, line, problems);
            long span = Long.parseLong(m.group(2));
            if (n == null) return;
            if (span < 1) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'"
                                        + rule
                                        + "': the span must be at least 1. Write it as access_denied: 3 in 60s"));
                return;
            }
            b.accessCount = n;
            b.accessSeconds =
                    span
                            * switch (m.group(3)) {
                                case "s" -> 1L;
                                case "m" -> 60L;
                                default -> 3600L;
                            };
        } else if ((m = INJECTION.matcher(rule)).matches()) {
            b.injectionScan = "on".equals(m.group(1));
        } else if ((m = TRIFECTA.matcher(rule)).matches()) {
            if (m.group(1).isBlank()) {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "'"
                                        + rule
                                        + "': an accepted trifecta needs a reason, written in quotes, so the acceptance is never silent. "
                                        + example("trifecta")));
                return;
            }
            b.trifectaAccepted = m.group(1).strip();
        } else {
            String word = rule.contains(":") ? rule.substring(0, rule.indexOf(':')).strip() : rule;
            if (RULES.contains(word)) {
                problems.add(
                        new Problem(line, true, "cannot read '" + rule + "'. " + example(word)));
            } else {
                problems.add(
                        new Problem(
                                line,
                                true,
                                "unknown rule '"
                                        + word
                                        + "'"
                                        + Nearest.hint(word, RULES)
                                        + ". The rules are: "
                                        + String.join(", ", RULES)
                                        + "."));
            }
        }
    }

    private static void percent(
            Builder b,
            String level,
            String digits,
            String rule,
            int line,
            List<Problem> problems,
            int min,
            int max) {
        long n = Long.parseLong(digits);
        if (n < min || n > max) {
            problems.add(
                    new Problem(
                            line,
                            true,
                            "'"
                                    + rule
                                    + "': a percentage of the window must be between "
                                    + min
                                    + "% and "
                                    + max
                                    + "%"));
            return;
        }
        if (level.equals("warn")) b.ctxWarn = n / 100.0;
        else b.ctxHigh = n / 100.0;
    }

    private static void setLevel(
            Builder b, String level, Integer n, int line, List<Problem> problems) {
        if (n == null) return;
        if (level.equals("warn")) b.warn = n;
        else b.high = n;
    }

    private static Integer whole(String digits, String rule, int line, List<Problem> problems) {
        long n = Long.parseLong(digits);
        if (n < 1 || n > 100_000) {
            problems.add(new Problem(line, true, "'" + rule + "' takes a whole number from 1 up"));
            return null;
        }
        return (int) n;
    }

    private static String example(String rule) {
        return switch (rule) {
            case "no_progress" -> "Write it as no_progress: 3 rounds on review.score";
            case "ungrounded_figures" -> "Write it as ungrounded_figures: warn at 2, high at 4";
            case "paused_longer_than" -> "Write it as paused_longer_than: 90s (s, m, h or d)";
            case "context_nearly_full" -> "Write it as context_nearly_full: warn at 85%, high at 95%";
            case "truncated_answers" -> "Write it as truncated_answers: 1 times";
            case "compaction_thrash" -> "Write it as compaction_thrash: 4 in 10 calls";
            case "tool_success_rate" -> "Write it as tool_success_rate: below 70% over 10 calls";
            case "latency_spike" -> "Write it as latency_spike: 5x p50";
            case "access_denied" -> "Write it as access_denied: 3 in 60s (s, m or h)";
            case "injection_scan" -> "Write it as injection_scan: on, or injection_scan: off";
            case "trifecta" -> "Write it as trifecta: accepted \"customer support: reads tickets, looks up the account, replies\" (the reason is required and has no double quotes)";
            default -> "Write it as " + rule + ": 4 times";
        };
    }
}
