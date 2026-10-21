package io.github.llm4j.loom.execution;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a person may type to a run that is going on in this process, from the console or a chat. Who
 * may type it is decided before it gets here (the chat's allowlist; the console is the person who
 * started the run). Every command is answered in words, and every refusal says why.
 *
 * <pre>
 * agents                                       who is running
 * steer [Agent] &lt;text&gt;                          correct its reasoning; the workflow is untouched
 * wrap up [Agent] [: reason]                   finish now with what it has
 * cancel [Agent] [outcome] [: reason]          outcome: use partial | skip | discard | retry | suspend | conclude | ask
 * cancel [Agent] back to &lt;checkpoint&gt; [: reason]   send the run back to a checkpoint the script declared
 * pause [Agent] | resume [Agent]
 * </pre>
 *
 * A command may start with {@code #label} to say which run, when several are going. The agent may
 * be left out when exactly one is running.
 */
public final class InterventionCommands {

    private static final Pattern VERB =
            Pattern.compile(
                    "^(?:#(\\S+)\\s+)?(agents|steer|wrap[ -]?up|cancel|pause|resume)\\b\\s*(.*)$",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BACK =
            Pattern.compile(
                    "^back\\s+to\\s+([^\\s:]+)\\s*(?::\\s*(.*))?$",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final LiveRuns live;

    public InterventionCommands(LiveRuns live) {
        this.live = live;
    }

    /** The commands of this process's live runs. */
    public static InterventionCommands forThisProcess() {
        return new InterventionCommands(LiveRuns.shared());
    }

    /** Whether the text starts like a command (it may still be refused). */
    public boolean isCommand(String text) {
        return text != null && VERB.matcher(text.strip()).matches();
    }

    /**
     * Runs the command; the answer is what to tell the person. {@code origin} is who asked, for the
     * journal and the audit.
     */
    public String handle(String text, String origin) {
        Matcher m = VERB.matcher(text == null ? "" : text.strip());
        if (!m.matches())
            return "I did not understand that. Try: agents, steer, wrap up, cancel, pause, resume.";
        String label = m.group(1);
        String verb = m.group(2).toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
        String rest = m.group(3).strip();

        var runs = live.all();
        if (label != null) runs = runs.stream().filter(r -> r.label().equals(label)).toList();
        if (runs.isEmpty())
            return label != null
                    ? "No run called " + label + " is going on here."
                    : "Nothing is running here.";

        if (verb.equals("agents")) {
            StringBuilder b = new StringBuilder();
            for (var r : runs) {
                Map<String, String> agents = r.executor().runningAgents();
                b.append('#')
                        .append(r.label())
                        .append(": ")
                        .append(
                                agents.isEmpty()
                                        ? "no agent is working right now"
                                        : String.join(
                                                ", ", new java.util.TreeSet<>(agents.values())))
                        .append('\n');
            }
            return b.toString().strip();
        }

        // which agent: the first word if some run has it working, else the only one working
        String agent = null;
        String after = rest;
        String first = rest.isEmpty() ? "" : rest.split("\\s+", 2)[0].replaceAll(":$", "");
        final String firstWord = first;
        boolean named =
                !first.isEmpty()
                        && runs.stream()
                                .anyMatch(
                                        r -> r.executor().runningAgents().containsValue(firstWord));
        if (named) {
            agent = first;
            after = rest.substring(rest.indexOf(first) + first.length()).strip();
        } else {
            var all = new ArrayList<String>();
            for (var r : runs)
                all.addAll(new java.util.TreeSet<>(r.executor().runningAgents().values()));
            var distinct = all.stream().distinct().toList();
            if (distinct.isEmpty()) return "No agent is working right now.";
            if (distinct.size() > 1)
                return "Which agent? Working now: " + String.join(", ", distinct) + ".";
            agent = distinct.get(0);
        }
        final String target = agent;
        var matches =
                runs.stream()
                        .filter(r -> r.executor().runningAgents().containsValue(target))
                        .toList();
        if (matches.size() > 1) {
            return target
                    + " is working in more than one run; start the command with #label: "
                    + String.join(", ", matches.stream().map(r -> "#" + r.label()).toList())
                    + ".";
        }
        HarnessExecutor ex = matches.get(0).executor();

        switch (verb) {
            case "steer":
                {
                    if (after.isEmpty())
                        return "Steer "
                                + target
                                + " with what? e.g. steer "
                                + target
                                + " focus on the EU market";
                    int n = ex.steer(target, after, origin);
                    return n > 0
                            ? "Sent to " + target + ". It reads it before its next step."
                            : "Not sent: "
                                    + target
                                    + " is not steerable, the text is empty or too long, or its guard refused it.";
                }
            case "wrapup":
                {
                    int n = ex.wrapUp(target, reasonOf(after), origin);
                    return n > 0
                            ? target + " is finishing with what it has."
                            : "Nothing to wrap up.";
                }
            case "pause":
                return ex.pauseAgents(target, origin) > 0
                        ? target + " pauses at its next step."
                        : "Nothing to pause.";
            case "resume":
                return ex.resumeAgents(target, origin) > 0
                        ? target + " carries on."
                        : "Nothing to resume.";
            case "cancel":
                return cancel(ex, target, after, origin);
            default:
                return "I did not understand that.";
        }
    }

    private static String reasonOf(String text) {
        String t = text.strip();
        if (t.startsWith(":")) t = t.substring(1).strip();
        return t.isEmpty() ? "asked to wrap up" : t;
    }

    private static String cancel(HarnessExecutor ex, String agent, String after, String origin) {
        if (after.toLowerCase(Locale.ROOT).startsWith("back to ")) {
            Matcher b = BACK.matcher(after);
            if (b.matches()) {
                String reason =
                        b.group(2) == null || b.group(2).isBlank()
                                ? "sent back"
                                : b.group(2).strip();
                int n = ex.cancelBack(agent, b.group(1), reason, origin);
                return n > 0
                        ? agent
                                + " is stopped and the run goes back to "
                                + b.group(1)
                                + ", carrying your reason."
                        : "Not sent back: the script must declare the checkpoint "
                                + b.group(1)
                                + ", and this step must have an on_stopped rewind that allows it.";
            }
        }
        String outcomeText = after;
        String reason = "stopped by a person";
        int colon = after.indexOf(':');
        if (colon >= 0) {
            outcomeText = after.substring(0, colon).strip();
            reason = after.substring(colon + 1).strip();
            if (reason.isEmpty()) reason = "stopped by a person";
        }
        StopOutcome outcome = null;
        if (!outcomeText.isEmpty()) {
            outcome = outcomeOf(outcomeText);
            if (outcome == null)
                return "I do not know the outcome \""
                        + outcomeText
                        + "\". Use: use partial, skip, discard, retry, suspend, conclude, ask, or back to <checkpoint>.";
        }
        int n = ex.cancel(agent, reason, origin, outcome);
        return n > 0
                ? agent
                        + " is stopped"
                        + (outcome == null
                                ? "; the run will ask what to do."
                                : ": "
                                        + outcome.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                        + ".")
                : "Nothing to cancel.";
    }

    static StopOutcome outcomeOf(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
        return switch (t) {
            case "use partial", "partial", "keep" -> StopOutcome.USE_PARTIAL;
            case "skip" -> StopOutcome.SKIP;
            case "discard", "drop" -> StopOutcome.DISCARD;
            case "retry", "again" -> StopOutcome.RETRY;
            case "suspend", "pause run" -> StopOutcome.SUSPEND;
            case "conclude", "end", "stop" -> StopOutcome.CONCLUDE;
            case "ask" -> StopOutcome.ASK;
            default -> null;
        };
    }
}
