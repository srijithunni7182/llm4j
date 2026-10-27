package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The reference for the commands of {@code weave}, written from the commands themselves (their
 * names, options, defaults and descriptions) so that it cannot say something the command does not.
 * What a command cannot say about itself (examples, exit codes, what to read next) is kept in
 * {@code man/pages/<command>.txt}, one file for each command, in sections that start with a line
 * {@code ## EXAMPLES}, {@code ## EXIT STATUS}, {@code ## SEE ALSO} or {@code ## DESCRIPTION}. The
 * order of the commands, by what a person is doing, is in {@code man/groups.txt}.
 */
public final class ManPage {

    private ManPage() {}

    /** One page, ready to print in either form. */
    public record Page(
            String path,
            String summary,
            String synopsis,
            List<String> description,
            List<String[]> arguments,
            List<String[]> options,
            List<String[]> commands,
            Map<String, String> sections) {}

    /** The pages of a command and of all the commands below it, depth first. */
    public static List<Page> pages(CommandLine command, String prefix) {
        List<Page> out = new ArrayList<>();
        out.add(page(command, prefix));
        for (var e : command.getSubcommands().entrySet()) {
            if (e.getValue().getCommandSpec().usageMessage().hidden()) continue;
            if (!e.getKey().equals(e.getValue().getCommandName())) continue; // an alias
            out.addAll(pages(e.getValue(), prefix + " " + e.getKey()));
        }
        return out;
    }

    public static Page page(CommandLine command, String path) {
        CommandSpec spec = command.getCommandSpec();
        List<String> description = new ArrayList<>(List.of(spec.usageMessage().description()));
        Map<String, String> extra = extras(path);
        if (extra.containsKey("DESCRIPTION")) description.add(extra.remove("DESCRIPTION"));
        List<String[]> arguments = new ArrayList<>();
        StringBuilder synopsis = new StringBuilder(path);
        boolean anyOption = false;
        for (OptionSpec o : spec.options()) {
            if (o.hidden() || o.usageHelp() || o.versionHelp()) continue;
            anyOption = true;
        }
        if (anyOption) synopsis.append(" [OPTIONS]");
        if (!command.getSubcommands().isEmpty()) synopsis.append(" <command>");
        for (PositionalParamSpec p : spec.positionalParameters()) {
            String label = p.paramLabel().replaceAll("^<|>$", "");
            boolean many = p.isMultiValue();
            String shown = "<" + label + ">" + (many ? "..." : "");
            synopsis.append(' ').append(p.required() ? shown : "[" + shown + "]");
            arguments.add(new String[] {shown, String.join(" ", p.description())});
        }
        List<String[]> options = new ArrayList<>();
        for (OptionSpec o : spec.options()) {
            if (o.hidden() || o.usageHelp() || o.versionHelp()) continue;
            options.add(new String[] {optionName(o), optionText(o)});
        }
        options.sort(
                (a, b) ->
                        a[0].replaceAll("^-+|, -+", "")
                                .compareToIgnoreCase(b[0].replaceAll("^-+|, -+", "")));
        List<String[]> commands = new ArrayList<>();
        for (var e : command.getSubcommands().entrySet()) {
            CommandLine sub = e.getValue();
            if (sub.getCommandSpec().usageMessage().hidden()) continue;
            if (!e.getKey().equals(sub.getCommandName())) continue;
            commands.add(
                    new String[] {
                        e.getKey(), first(sub.getCommandSpec().usageMessage().description())
                    });
        }
        return new Page(
                path,
                first(spec.usageMessage().description()),
                synopsis.toString(),
                description,
                arguments,
                options,
                commands,
                extra);
    }

    private static String first(String[] description) {
        if (description == null || description.length == 0) return "";
        String all = String.join(" ", description).strip();
        int stop = all.indexOf(". ");
        return stop < 0 ? all : all.substring(0, stop + 1);
    }

    private static String optionName(OptionSpec o) {
        StringBuilder sb = new StringBuilder(String.join(", ", o.names()));
        if (o.arity().max() > 0 && !o.typeInfo().isBoolean()) {
            String label = o.paramLabel().replaceAll("^<|>$", "");
            sb.append(o.names()[0].startsWith("--") ? "=" : " ")
                    .append("<")
                    .append(label)
                    .append(">");
        }
        return sb.toString();
    }

    private static String optionText(ArgSpec o) {
        StringBuilder sb = new StringBuilder(String.join(" ", o.description()));
        if (o.isMultiValue() && !sb.toString().contains("repeatable")) sb.append(" (repeatable)");
        String def = o.defaultValue();
        if (def != null
                && !def.isBlank()
                && !def.equals("false")
                && !def.equals("null")
                && !sb.toString().toLowerCase().contains("default"))
            sb.append(" (default: ").append(def).append(")");
        return sb.toString().strip();
    }

    // ---- what a command cannot say about itself

    /** The sections written by hand for a command, by heading. */
    static Map<String, String> extras(String path) {
        String file = path.replace(' ', '-') + ".txt";
        String text = resource("man/pages/" + file);
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                if (heading != null) out.put(heading, body.toString().strip());
                heading = line.substring(3).strip();
                body.setLength(0);
            } else if (heading != null) body.append(line).append('\n');
        }
        if (heading != null) out.put(heading, body.toString().strip());
        return out;
    }

    /** The groups, in order: name and the commands in it. */
    public static Map<String, List<String>> groups() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String text = resource("man/groups.txt");
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            out.put(
                    line.substring(0, colon).strip(),
                    List.of(line.substring(colon + 1).strip().split("\\s+")));
        }
        return out;
    }

    private static String resource(String name) {
        try (InputStream in = ManPage.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- text, like man

    public static String text(Page p) {
        StringBuilder sb = new StringBuilder();
        String title = p.path().toUpperCase();
        sb.append(title).append("\n\n");
        sb.append("NAME\n")
                .append(indent(wrap(p.path() + " - " + lower(p.summary()), 72), 7))
                .append("\n\n");
        sb.append("SYNOPSIS\n").append(indent(p.synopsis(), 7)).append("\n\n");
        if (!p.description().isEmpty()) {
            sb.append("DESCRIPTION\n");
            for (String d : p.description()) sb.append(indent(wrap(d, 72), 7)).append("\n\n");
        }
        if (!p.arguments().isEmpty()) {
            sb.append("ARGUMENTS\n");
            for (String[] a : p.arguments())
                sb.append(indent(a[0], 7))
                        .append('\n')
                        .append(indent(wrap(a[1], 64), 15))
                        .append("\n\n");
        }
        if (!p.commands().isEmpty()) {
            sb.append("COMMANDS\n");
            for (String[] c : p.commands())
                sb.append(indent(c[0], 7))
                        .append('\n')
                        .append(indent(wrap(c[1], 64), 15))
                        .append("\n\n");
        }
        if (!p.options().isEmpty()) {
            sb.append("OPTIONS\n");
            for (String[] o : p.options())
                sb.append(indent(o[0], 7))
                        .append('\n')
                        .append(indent(wrap(o[1], 64), 15))
                        .append("\n\n");
        }
        for (var e : p.sections().entrySet())
            sb.append(e.getKey()).append('\n').append(indent(e.getValue(), 7)).append("\n\n");
        if (!p.sections().containsKey("EXIT STATUS"))
            sb.append("EXIT STATUS\n")
                    .append(
                            indent(
                                    "0 done; 1 the command ran and found something (a failed check, a finding); 2 it could not run (bad arguments, a missing file, a model or key problem).",
                                    7))
                    .append("\n");
        return sb.toString().stripTrailing() + "\n";
    }

    // ---- markdown, for the reference in the repository

    public static String markdown(Page p, int level) {
        String h = "#".repeat(level);
        StringBuilder sb = new StringBuilder();
        sb.append(h).append(" `").append(p.path()).append("`\n\n");
        sb.append(p.summary()).append("\n\n");
        sb.append("```\n").append(p.synopsis()).append("\n```\n\n");
        for (int i = 1; i < p.description().size(); i++)
            sb.append(p.description().get(i)).append("\n\n");
        if (p.description().size() > 0
                && p.summary().length() < p.description().get(0).strip().length()) {
            String rest = p.description().get(0).strip().substring(p.summary().length()).strip();
            if (!rest.isEmpty()) sb.insert(sb.indexOf("```"), rest + "\n\n");
        }
        if (!p.arguments().isEmpty()) {
            sb.append("**Arguments**\n\n");
            for (String[] a : p.arguments())
                sb.append("- `").append(a[0]).append("` — ").append(a[1]).append('\n');
            sb.append('\n');
        }
        if (!p.commands().isEmpty()) {
            sb.append("**Commands**\n\n");
            for (String[] c : p.commands())
                sb.append("- `").append(c[0]).append("` — ").append(c[1]).append('\n');
            sb.append('\n');
        }
        if (!p.options().isEmpty()) {
            sb.append("**Options**\n\n");
            for (String[] o : p.options())
                sb.append("- `").append(o[0]).append("` — ").append(o[1]).append('\n');
            sb.append('\n');
        }
        for (var e : p.sections().entrySet()) {
            sb.append("**").append(title(e.getKey())).append("**\n\n");
            sb.append(
                            e.getKey().equals("EXAMPLES")
                                    ? "```\n" + e.getValue() + "\n```"
                                    : e.getValue())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private static String title(String s) {
        String l = s.toLowerCase();
        return Character.toUpperCase(l.charAt(0)) + l.substring(1);
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String indent(String s, int n) {
        String pad = " ".repeat(n);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\R", -1))
            sb.append(line.isEmpty() ? "" : pad + line).append('\n');
        return sb.toString().stripTrailing();
    }

    private static String wrap(String s, int width) {
        StringBuilder out = new StringBuilder();
        for (String para : s.split("\\R")) {
            StringBuilder line = new StringBuilder();
            for (String w : para.split("\\s+")) {
                if (line.length() + w.length() + 1 > width && line.length() > 0) {
                    out.append(line).append('\n');
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(w);
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
