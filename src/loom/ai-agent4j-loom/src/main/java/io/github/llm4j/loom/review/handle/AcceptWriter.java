package io.github.llm4j.loom.review.handle;

import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes accepts into {@code review.properties}: the durable "I know, and here is why". The file is
 * read by people in a pull request, so each entry says what it covers, why, until when, by whom and
 * on what day. Nothing else in the file is touched.
 *
 * <p>Rules it enforces: a reason is required; the lethal trifecta (LA01) is not accepted here
 * because audit and Fabric read that decision in the script itself ({@code trifecta: accepted
 * "reason"}); and accepting more than one finding at a time for a serious rule needs the number
 * typed back.
 */
public final class AcceptWriter {

    /** How much one accept covers. */
    public enum Scope {
        /** just this finding, by fingerprint */
        FINDING,
        /** the rule where the finding names the same thing (an agent, a workflow) */
        THING,
        /** the rule anywhere in this script */
        SCRIPT,
        /** the rule anywhere in the project */
        PROJECT
    }

    private AcceptWriter() {}

    /** The line for one accept, or an error message as {@link IllegalArgumentException}. */
    public static String line(
            Finding f,
            Scope scope,
            String thing,
            String reason,
            LocalDate until,
            String by,
            LocalDate at) {
        if (f.rule().equals("LA01"))
            throw new IllegalArgumentException(
                    "LA01 (the lethal trifecta) is accepted in the script, not here: write trifecta: accepted \"your reason\" in the script, because audit and Fabric read it there.");
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("an accept needs a reason: say why this is fine.");
        if (reason.contains("\""))
            throw new IllegalArgumentException(
                    "the reason cannot contain a double quote; use single quotes.");
        String where =
                switch (scope) {
                    case FINDING -> "finding " + f.fingerprint();
                    case THING -> {
                        if (thing == null || thing.isBlank())
                            throw new IllegalArgumentException(
                                    "this scope needs the name of the thing (an agent, a workflow).");
                        yield "on " + thing;
                    }
                    case SCRIPT -> "script " + f.where().file();
                    case PROJECT -> "project";
                };
        StringBuilder b =
                new StringBuilder(f.rule())
                        .append(".accept = ")
                        .append(where)
                        .append(" \"")
                        .append(reason.strip())
                        .append('"');
        if (until != null) b.append(" until ").append(until);
        if (by != null && !by.isBlank())
            b.append(" by \"").append(by.replace("\"", "'")).append('"');
        if (at != null) b.append(" at ").append(at);
        return b.toString();
    }

    /** True for findings whose bulk accept needs the count confirmed. */
    public static boolean serious(Finding f) {
        return f.severity() == Severity.HIGH
                || f.lens() == Lens.SECURITY
                || f.lens() == Lens.DURABILITY
                || f.lens() == Lens.PRIVACY;
    }

    /** The text of the properties file after the lines are added, and how many were new. */
    public record Appended(String text, int written) {}

    /**
     * The file's text with the lines added (those already there are skipped), without writing it:
     * the editor writes the file so that undo and unsaved buffers stay right.
     */
    public static Appended appended(String existing, List<String> lines) {
        List<String> all =
                new ArrayList<>(existing == null ? List.of() : existing.lines().toList());
        int written = 0;
        for (String l : lines) {
            if (all.contains(l)) continue;
            all.add(l);
            written++;
        }
        return new Appended(String.join("\n", all) + "\n", written);
    }

    /**
     * Appends accept lines to the file, creating it when needed.
     *
     * @return how many lines were written
     */
    public static int append(Path file, List<String> lines) {
        try {
            List<String> all =
                    Files.isRegularFile(file)
                            ? new ArrayList<>(Files.readAllLines(file))
                            : new ArrayList<>();
            int written = 0;
            for (String l : lines) {
                if (all.contains(l)) continue;
                all.add(l);
                written++;
            }
            Files.write(file, all);
            return written;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " could not be written: " + e.getMessage());
        }
    }
}
