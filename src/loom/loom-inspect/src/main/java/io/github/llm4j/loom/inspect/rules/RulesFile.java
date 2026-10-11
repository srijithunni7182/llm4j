package io.github.llm4j.loom.inspect.rules;

import io.github.llm4j.loom.autonomy.expr.Suggest;
import io.github.llm4j.loom.inspect.state.InspectRules;
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
 * The console's rules for what is trouble, kept <b>beside</b> a workflow and not inside it: {@code flow.inspect} next to
 * {@code flow.loom}, or any file given with {@code --observe-rules}. The Loom script says what the workflow does; this file says how it
 * is watched, so the script stays as readable as it was, and the same script can be watched differently in test and in production.
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
 * Rules: {@code same_action N times}, {@code same_output N times}, {@code tool_failures N times}, {@code no_progress N rounds on <variable>},
 * {@code ungrounded_figures warn at N, high at M}, {@code paused_longer_than <30s|5m|2h>}. One per line, or several on one line separated
 * by {@code ;}. {@code #} and {@code //} start a comment. It changes nothing about how a run behaves.
 */
public final class RulesFile {

    /** The name a block uses for "every agent". */
    public static final String ALL = "*";

    public static final List<String> RULES = List.of("same_action", "same_output", "tool_failures", "no_progress", "ungrounded_figures", "paused_longer_than");

    /** Something wrong with the file or with how it meets the script. */
    public record Problem(int line, boolean error, String message) {
        @Override
        public String toString() {
            return (error ? "error" : "warning") + (line > 0 ? " line " + line : "") + ": " + message;
        }
    }

    /** What was read: the rules by agent name (the key {@code *} is "every agent") and the lines they came from. */
    public record Parsed(Map<String, InspectRules> rules, Map<String, Integer> lines, List<Problem> problems) {
        public boolean ok() {
            return problems.stream().noneMatch(Problem::error);
        }
    }

    private static final Pattern COUNT = Pattern.compile("(same_action|same_output|tool_failures)\\s*:\\s*(\\d+)\\s+times");
    private static final Pattern PROGRESS = Pattern.compile("no_progress\\s*:\\s*(\\d+)\\s+rounds\\s+on\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern UNGROUNDED = Pattern.compile("ungrounded_figures\\s*:\\s*(warn|high)\\s+at\\s+(\\d+)(?:\\s*,\\s*(warn|high)\\s+at\\s+(\\d+))?");
    private static final Pattern PAUSED = Pattern.compile("paused_longer_than\\s*:\\s*(\\d+)\\s*([smhd])");
    private static final Pattern OPEN = Pattern.compile("^(\\*|[A-Za-z_][A-Za-z0-9_]*)\\s*\\{(.*)$");

    private RulesFile() { }

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
            String line = all[i].replaceAll("(#|//).*$", "").strip();
            if (line.isEmpty()) continue;
            if (current == null) {
                Matcher m = OPEN.matcher(line);
                if (!m.matches()) {
                    problems.add(new Problem(no, true, "expected 'AgentName {' (or '* {' for every agent), got '" + line + "'"));
                    continue;
                }
                name = m.group(1);
                if (blocks.containsKey(name)) problems.add(new Problem(no, true, "there is already a block for " + name));
                current = new Builder();
                blocks.put(name, current);
                lines.put(name, no);
                line = m.group(2).strip();
                if (line.isEmpty()) continue;
            }
            boolean closes = line.endsWith("}");
            if (closes) line = line.substring(0, line.length() - 1).strip();
            for (String part : line.split(";")) {
                String rule = part.strip();
                if (!rule.isEmpty()) apply(rule, no, current, name, problems);
            }
            if (closes) current = null;
        }
        if (current != null) problems.add(new Problem(all.length, true, "the block for " + name + " is not closed with '}'"));
        Map<String, InspectRules> out = new LinkedHashMap<>();
        for (Map.Entry<String, Builder> b : blocks.entrySet()) {
            Builder v = b.getValue();
            if (v.empty()) {
                problems.add(new Problem(lines.get(b.getKey()), false, "the block for " + b.getKey() + " sets no rule, so it does nothing"));
                continue;
            }
            if (v.warn != null && v.high != null && v.warn >= v.high) {
                problems.add(new Problem(lines.get(b.getKey()), true, b.getKey() + ": ungrounded_figures warn at " + v.warn + " must be lower than high at " + v.high));
            }
            if (ALL.equals(b.getKey()) && v.progressVar != null) {
                problems.add(new Problem(lines.get(b.getKey()), true, "no_progress names a variable that belongs to one agent's loop: put it in that agent's block, not in *"));
            }
            out.put(b.getKey(), v.build());
        }
        return new Parsed(out, lines, problems);
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

        boolean empty() {
            return sameAction == null && sameOutput == null && toolFailures == null && warn == null && high == null && paused == null && rounds == null;
        }

        InspectRules build() {
            return new InspectRules(sameAction, sameOutput, toolFailures, warn, high, paused, rounds, progressVar);
        }
    }

    private static void apply(String rule, int line, Builder b, String block, List<Problem> problems) {
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
            if (m.group(3) != null) setLevel(b, m.group(3), whole(m.group(4), rule, line, problems), line, problems);
        } else if ((m = PAUSED.matcher(rule)).matches()) {
            long n = Long.parseLong(m.group(1));
            if (n < 1) {
                problems.add(new Problem(line, true, "paused_longer_than must be positive, got '" + rule + "'"));
                return;
            }
            b.paused = n * switch (m.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3600L;
                default -> 86400L;
            };
        } else {
            String word = rule.contains(":") ? rule.substring(0, rule.indexOf(':')).strip() : rule;
            if (RULES.contains(word)) {
                problems.add(new Problem(line, true, "cannot read '" + rule + "'. " + example(word)));
            } else {
                problems.add(new Problem(line, true, "unknown rule '" + word + "'" + Suggest.hint(word, RULES) + ". The rules are: " + String.join(", ", RULES) + "."));
            }
        }
    }

    private static void setLevel(Builder b, String level, Integer n, int line, List<Problem> problems) {
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
            default -> "Write it as " + rule + ": 4 times";
        };
    }
}
