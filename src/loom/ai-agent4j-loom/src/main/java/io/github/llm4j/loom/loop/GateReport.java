package io.github.llm4j.loom.loop;

import java.util.List;

/**
 * The verdict of {@code weave gate}: each stage passed, failed or was skipped, with the one command
 * that reproduces it, and one word for the whole.
 */
public record GateReport(
        String script, List<Stage> stages, List<String> changedFiles, List<String> required) {

    public enum Status {
        PASS,
        FAIL,
        SKIPPED
    }

    public record Stage(String name, Status status, String summary, String reproduce) {}

    /** A required stage that was skipped is a failure: it never passes silently. */
    public boolean passed() {
        for (Stage s : stages) {
            if (s.status() == Status.FAIL) return false;
            if (s.status() == Status.SKIPPED && required.contains(s.name())) return false;
        }
        return true;
    }

    public List<Stage> failed() {
        return stages.stream()
                .filter(
                        s ->
                                s.status() == Status.FAIL
                                        || (s.status() == Status.SKIPPED
                                                && required.contains(s.name())))
                .toList();
    }

    public String text() {
        StringBuilder b = new StringBuilder("Gate  ").append(script).append("\n\n");
        for (Stage s : stages) {
            b.append(String.format("  %-8s %-8s %s%n", s.name(), label(s), s.summary()));
            if (s.status() == Status.FAIL
                    || (s.status() == Status.SKIPPED && required.contains(s.name())))
                b.append("           reproduce: ").append(s.reproduce()).append('\n');
        }
        if (!changedFiles.isEmpty())
            b.append("\n  Changed files: ").append(String.join(", ", changedFiles)).append('\n');
        b.append("\n  ")
                .append(
                        passed()
                                ? "PASSED"
                                : "FAILED: "
                                        + String.join(
                                                ", ", failed().stream().map(Stage::name).toList()))
                .append('\n');
        return b.toString();
    }

    private String label(Stage s) {
        if (s.status() == Status.SKIPPED && required.contains(s.name()))
            return "SKIPPED (required)";
        return s.status().name();
    }

    public String markdown() {
        StringBuilder b =
                new StringBuilder("### Gate: `")
                        .append(script)
                        .append("` ")
                        .append(passed() ? "passed" : "failed")
                        .append("\n\n");
        b.append("| stage | result | |\n|---|---|---|\n");
        for (Stage s : stages)
            b.append("| ")
                    .append(s.name())
                    .append(" | ")
                    .append(label(s).toLowerCase())
                    .append(" | ")
                    .append(s.summary().replace("|", "\\|"))
                    .append(" |\n");
        for (Stage s : failed())
            b.append("\nTo reproduce `")
                    .append(s.name())
                    .append("`: `")
                    .append(s.reproduce())
                    .append("`\n");
        if (!changedFiles.isEmpty())
            b.append("\nChanged files: ")
                    .append(
                            String.join(
                                    ", ", changedFiles.stream().map(f -> "`" + f + "`").toList()))
                    .append('\n');
        return b.toString();
    }

    public String junit() {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<testsuite name=\"gate ")
                .append(RegressReport.esc(script))
                .append("\" tests=\"")
                .append(stages.size())
                .append("\" failures=\"")
                .append(failed().size())
                .append("\" skipped=\"")
                .append(stages.stream().filter(s -> s.status() == Status.SKIPPED).count())
                .append("\">\n");
        for (Stage s : stages) {
            b.append("  <testcase classname=\"gate\" name=\"").append(s.name()).append("\">");
            if (failed().contains(s))
                b.append("<failure message=\"")
                        .append(RegressReport.esc(s.summary()))
                        .append("\">reproduce: ")
                        .append(RegressReport.esc(s.reproduce()))
                        .append("</failure>");
            else if (s.status() == Status.SKIPPED)
                b.append("<skipped message=\"")
                        .append(RegressReport.esc(s.summary()))
                        .append("\"/>");
            b.append("</testcase>\n");
        }
        return b.append("</testsuite>\n").toString();
    }
}
