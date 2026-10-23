package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.debug.Stepper;
import io.github.llm4j.loom.execution.VariableView;
import io.github.llm4j.loom.explain.Why;
import io.github.llm4j.loom.explain.WhyRenderer;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The conversation of {@code weave step}: reads a command, does it on the {@link Stepper}, shows
 * where the run is. It keeps no state of its own, so a recorded list of commands replays to the
 * same conversation.
 */
final class StepSession {

    private final Stepper stepper;
    private final Path script;
    private final String workflow;
    private final Map<String, String> inputs;
    private final Set<String> masked;
    private final PrintStream out;
    private final PrintStream recorder;
    private final boolean echo;

    StepSession(
            Stepper stepper,
            Path script,
            String workflow,
            Map<String, String> inputs,
            Set<String> masked,
            PrintStream out,
            PrintStream recorder,
            boolean echo) {
        this.stepper = stepper;
        this.script = script;
        this.workflow = workflow;
        this.inputs = inputs;
        this.masked = masked;
        this.out = out;
        this.recorder = recorder;
        this.echo = echo;
    }

    /** Runs the conversation until {@code quit} or the end of the input. */
    void run(BufferedReader in) throws IOException {
        show(stepper.state());
        String line;
        while (true) {
            out.print("> ");
            out.flush();
            line = in.readLine();
            if (line == null) {
                out.println();
                return;
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (echo) out.println(line);
            if (recorder != null) recorder.println(line);
            if (!command(line)) return;
        }
    }

    /** Does one command; false when the person is done. */
    boolean command(String line) {
        String[] parts = line.split("\\s+", 2);
        String word = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].strip() : "";
        try {
            switch (word) {
                case "next", "n" -> show(stepper.move(Stepper.Move.OVER));
                case "into", "i", "step", "s" -> show(stepper.move(Stepper.Move.INTO));
                case "out", "o" -> show(stepper.move(Stepper.Move.OUT));
                case "continue", "c" -> show(stepper.move(Stepper.Move.CONTINUE));
                case "back", "b" -> show(stepper.back());
                case "break", "br" -> {
                    if (rest.isEmpty()) listBreakpoints();
                    else {
                        var b = stepper.breakAt(rest);
                        out.println("Breakpoint " + b.id() + ": " + b.describe());
                    }
                }
                case "breaks" -> listBreakpoints();
                case "unbreak" -> {
                    if (rest.equals("all") || rest.isEmpty()) {
                        stepper.breakpoints().clear();
                        out.println("No breakpoints.");
                    } else if (stepper.breakpoints().remove(Integer.parseInt(rest)))
                        out.println("Removed breakpoint " + rest + ".");
                    else out.println("There is no breakpoint " + rest + ".");
                }
                case "vars" -> variables();
                case "p", "print" -> {
                    if (rest.isEmpty()) variables();
                    else out.println(rest + " = " + shown(stepper.value(rest)));
                }
                case "set" -> {
                    int eq = rest.indexOf('=');
                    if (eq < 1) throw new IllegalArgumentException("Write: set name = value");
                    show(
                            stepper.set(
                                    rest.substring(0, eq).strip(),
                                    unquote(rest.substring(eq + 1).strip())));
                }
                case "answer" -> show(stepper.answer(unquote(rest)));
                case "model" -> {
                    String[] two = rest.split("\\s+", 2);
                    if (two.length < 2)
                        throw new IllegalArgumentException("Write: model Agent \"reply\"");
                    stepper.model(two[0], unquote(two[1]));
                    out.println(two[0] + " will say that at its next call.");
                }
                case "classifier" -> {
                    String[] two = rest.split("\\s+", 2);
                    if (two.length < 2)
                        throw new IllegalArgumentException(
                                "Write: classifier Name {\"question\": \"label\"}");
                    stepper.classifier(two[0], two[1]);
                    out.println(two[0] + " will answer that at its next call.");
                }
                case "why" -> why();
                case "where" -> where();
                case "cost" -> cost();
                case "help", "h", "?" -> help();
                case "quit", "q", "exit" -> {
                    return false;
                }
                default -> out.println(
                        "I do not know \"" + word + "\". Type help for the commands.");
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.println(e.getMessage());
        }
        return true;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1);
        return s;
    }

    // ---- showing

    private void show(Stepper.State s) {
        if (s.finished()) {
            out.println();
            out.println(
                    "■ The run "
                            + s.status()
                            + (s.reason().isBlank() ? "" : ": " + safe(s.reason()))
                            + ".");
            if (s.result() != null) out.println("  result = " + shown(s.result()));
            if ("suspended".equals(s.status()))
                out.println("  Give the answer with: answer <text>");
            out.println("  back goes to the last statement; quit ends.");
            return;
        }
        var p = s.position();
        out.println();
        out.println(
                "▶ "
                        + (p.line() > 0 ? "line " + p.line() + "  " : "")
                        + safe(p.statement())
                        + "   (statement "
                        + (p.index() + 1)
                        + (s.hit().startsWith("breakpoint") ? ", " + s.hit() : "")
                        + ")");
        for (String e : p.enclosing()) out.println("    inside: " + safe(e));
        if (!p.after().isEmpty()) out.println("    then:   " + safe(p.after().get(0)));
        for (VariableView v : s.variables()) out.println("    " + line(v));
    }

    private void variables() {
        var s = stepper.state();
        if (s.finished()) {
            out.println("The run has ended.");
            return;
        }
        if (s.variables().isEmpty()) out.println("No variables yet.");
        for (VariableView v : s.variables()) {
            out.println(
                    "  "
                            + line(v)
                            + "   ["
                            + v.type()
                            + (v.length() > v.value().length() ? ", " + v.length() + " chars" : "")
                            + "]");
        }
    }

    private static String line(VariableView v) {
        StringBuilder sb = new StringBuilder(v.name()).append(" = ").append(safe(v.value()));
        if (v.boundBy() != null)
            sb.append("   (")
                    .append(v.boundLine() > 0 ? "line " + v.boundLine() + ": " : "")
                    .append(safe(v.boundBy()))
                    .append(')');
        else if (!v.value().equals("‹masked›")) sb.append("   (input)");
        return sb.toString();
    }

    private void listBreakpoints() {
        var all = stepper.breakpoints().all();
        if (all.isEmpty())
            out.println("No breakpoints. break <line | n3 | Agent | when <condition>>");
        for (var b : all) out.println("  " + b.id() + "  " + b.describe());
    }

    private void where() {
        var s = stepper.state();
        if (s.finished()) {
            out.println("The run has ended (" + s.status() + ").");
            return;
        }
        var p = s.position();
        out.println(p.workflow());
        int depth = 1;
        for (String e : p.enclosing()) out.println("  ".repeat(depth++) + safe(e));
        out.println(
                "  ".repeat(depth)
                        + "▶ "
                        + (p.line() > 0 ? "line " + p.line() + "  " : "")
                        + safe(p.statement()));
        for (String a : p.after()) out.println("  ".repeat(depth) + "  " + safe(a));
    }

    private void cost() {
        var s = stepper.state();
        out.println(
                s.calls()
                        + " model calls, "
                        + s.tokens()
                        + " tokens so far."
                        + (s.calls() == 0 ? " (Models are mock here; nothing is spent.)" : ""));
    }

    private void why() {
        try {
            Why.Report report =
                    Why.explain(
                            script,
                            workflow,
                            inputs,
                            journalOf(),
                            new Why.Request(Why.Selector.none(), true, null),
                            masked);
            // a replay goes on past the pause through whatever needs no model; only what ran counts
            Set<String> ran = new java.util.HashSet<>(stepper.ranSteps());
            List<Why.Entry> entries =
                    report.entries().stream().filter(e -> ran.contains(e.step())).toList();
            if (entries.isEmpty()) {
                out.println("Nothing has been decided yet.");
                return;
            }
            Why.Entry last = entries.get(entries.size() - 1);
            for (int i = entries.size() - 1; i >= 0; i--)
                if (entries.get(i).taken() != null) {
                    last = entries.get(i);
                    break;
                }
            Why.Report one =
                    new Why.Report(
                            report.version(),
                            report.workflow(),
                            report.script(),
                            report.status(),
                            report.reason(),
                            report.stoppedAt(),
                            List.of(last),
                            List.of(),
                            null);
            String text = WhyRenderer.text(one);
            out.print(text.substring(text.indexOf("\n\n") + 2));
        } catch (Exception e) {
            out.println("Cannot explain: " + (e.getMessage() == null ? e : e.getMessage()));
        }
    }

    private RunJournal journalOf() {
        RunJournal j = RunJournal.inMemory();
        stepper.journal().forEach(j::put);
        return j;
    }

    private void help() {
        out.println(
                """
                  next, n        run this statement (all of it) and stop at the next
                  into, i        stop at the very next statement, even inside a branch or loop
                  out, o         run to the end of the block this statement is in
                  continue, c    run to a breakpoint or the end
                  back, b        go back one statement (the state there is re-derived)
                  break X        stop at a line, a node (n3), an Agent, or when <condition>
                  breaks / unbreak [id|all]
                  vars, p [name] show the variables / one value
                  set a = value  change a variable here (kept when you go back and on)
                  model A "text" what agent A says at its next call
                  classifier C {"question": "label"}   what a classifier answers next
                  answer text    the answer to a question the run waits on
                  why            why the last choice went the way it did
                  where, cost    the position in the workflow; what has been spent
                  quit, q        end"""
                        .stripIndent());
    }

    private static String shown(Object value) {
        String s = String.valueOf(value);
        return safe(s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }

    /** Control characters made visible: what a run holds is data and must not move the cursor. */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20
                    || c == 0x7f
                    || (c >= 0x80 && c < 0xa0)
                    || c == 0x202e
                    || (c >= 0x2066 && c <= 0x2069)) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
