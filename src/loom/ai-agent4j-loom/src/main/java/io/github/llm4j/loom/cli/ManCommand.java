package io.github.llm4j.loom.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code weave man}: the reference for the commands, like a man page. With nothing it lists the
 * commands by what a person is doing; with a command ({@code weave man generate}, {@code weave man
 * observe flush}) it prints that page; {@code --all} prints every page. It is written from the
 * commands themselves ({@link ManPage}), so it matches the jar it ships in.
 */
@Command(
        name = "man",
        description =
                "Prints the reference for a command, like a man page: what it does, every option with its default, examples, exit codes and what to read next. With no command it lists all of them by what you are doing. --all prints every page; --format markdown writes the repository's COMMAND_REFERENCE.md.")
final class ManCommand implements Callable<Integer> {

    @Spec CommandLine.Model.CommandSpec spec;

    @Parameters(
            arity = "0..*",
            paramLabel = "command",
            description = "The command, and the command under it if any (weave man observe flush).")
    List<String> command = new ArrayList<>();

    @Option(names = "--all", description = "Print the page of every command.")
    boolean all;

    @Option(names = "--format", description = "text (default) or markdown.")
    String format = "text";

    PrintStream out = System.out;
    PrintStream err = System.err;

    @Override
    public Integer call() {
        CommandLine root = spec.root().commandLine();
        boolean markdown = "markdown".equalsIgnoreCase(format);
        if (!markdown && !"text".equalsIgnoreCase(format)) {
            err.println("Error: --format is text or markdown.");
            return 2;
        }
        if (all) {
            out.print(markdown ? reference(root) : everyPage(root));
            return 0;
        }
        if (command.isEmpty()) {
            out.print(markdown ? index(root, true) : index(root, false));
            return 0;
        }
        CommandLine at = root;
        StringBuilder path = new StringBuilder("weave");
        for (String name : command) {
            CommandLine next = at.getSubcommands().get(name);
            if (next == null) {
                err.println(
                        "Error: weave has no command '"
                                + String.join(" ", command)
                                + "'. "
                                + closest(at, name)
                                + "Run 'weave man' to list them.");
                return 2;
            }
            at = next;
            path.append(' ').append(next.getCommandName());
        }
        ManPage.Page page = ManPage.page(at, path.toString());
        out.print(markdown ? ManPage.markdown(page, 2) : ManPage.text(page));
        return 0;
    }

    private static String closest(CommandLine at, String wanted) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : at.getSubcommands().keySet()) {
            int d = distance(name, wanted);
            if (d < bestDistance) {
                bestDistance = d;
                best = name;
            }
        }
        return best != null && bestDistance <= 3 ? "Did you mean '" + best + "'? " : "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] =
                        Math.min(
                                Math.min(cur[j - 1], prev[j]) + 1,
                                prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            prev = cur;
        }
        return prev[b.length()];
    }

    // ---- the list, by what a person is doing

    /** Every top-level command by group; one a group does not name is listed under "Other". */
    static String index(CommandLine root, boolean markdown) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<String>> groups = ManPage.groups();
        List<String> placed = new ArrayList<>();
        groups.values().forEach(placed::addAll);
        List<String> other = new ArrayList<>();
        for (String name : root.getSubcommands().keySet())
            if (!placed.contains(name)
                    && name.equals(root.getSubcommands().get(name).getCommandName()))
                other.add(name);
        if (!other.isEmpty()) groups.put("Other", other);
        if (!markdown) sb.append("weave: every command, by what you are doing\n\n");
        for (var g : groups.entrySet()) {
            sb.append(markdown ? "### " + g.getKey() + "\n\n" : g.getKey() + "\n");
            for (String name : g.getValue()) {
                CommandLine c = root.getSubcommands().get(name);
                if (c == null) continue;
                String summary = ManPage.page(c, "weave " + name).summary();
                if (markdown)
                    sb.append("- [`weave ")
                            .append(name)
                            .append("`](#weave-")
                            .append(name)
                            .append(") — ")
                            .append(summary)
                            .append('\n');
                else sb.append(String.format("  %-11s %s%n", name, shorten(summary, 62)));
            }
            sb.append('\n');
        }
        if (!markdown) sb.append("weave man <command> shows the page for a command.\n");
        return sb.toString();
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "...";
    }

    private static String everyPage(CommandLine root) {
        StringBuilder sb = new StringBuilder();
        for (ManPage.Page p : ManPage.pages(root, "weave"))
            sb.append(ManPage.text(p)).append("\n\f\n");
        return sb.toString();
    }

    /** The file {@code docs/COMMAND_REFERENCE.md}. */
    static String reference(CommandLine root) {
        StringBuilder sb = new StringBuilder();
        sb.append("# weave command reference\n\n");
        sb.append(
                "Written by `weave man --all --format markdown` from the commands themselves; do not edit it by hand. "
                        + "In a terminal, `weave man` lists the commands and `weave man <command>` prints one page.\n\n");
        sb.append("## Which command when\n\n");
        sb.append(index(root, true));
        sb.append("## Pages\n\n");
        for (ManPage.Page p : ManPage.pages(root, "weave")) {
            if (p.path().equals("weave")) continue;
            int depth = p.path().split(" ").length;
            sb.append(ManPage.markdown(p, depth == 2 ? 3 : 4));
        }
        return sb.toString();
    }
}
