package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.generate.GateResult;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.Finding;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.loom.security.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one way {@code weave connect} changes a script (phase 36d, D10): the new text is built, held to
 * the gates as a candidate file, shown as a diff, approved by a person and only then written. A
 * candidate that fails a gate is never written, and nothing here writes a secret: a declaration holds
 * a reference ({@code env.NAME}, {@code secret.NAME}, {@code invoker.NAME}) and never a value.
 */
final class ConnectEdit {

    /** What the gates found for a candidate. */
    record Verdict(List<String> errors, List<Finding> findings, boolean highFinding) {
        boolean passed() {
            return errors.isEmpty();
        }
    }

    private ConnectEdit() {}

    /** The script with {@code addition} appended after a blank line. */
    static String append(String source, String addition) {
        String base = source.endsWith("\n") ? source : source + "\n";
        return base + "\n" + addition.stripTrailing() + "\n";
    }

    /**
     * Runs {@code weave check} and the audit over the candidate text, as a file beside the script (so a
     * relative description or {@code import} resolves as it will). The candidate file is removed.
     *
     * @param newTools the names of the declarations being added: only their findings are reported
     */
    static Verdict gates(Path script, String candidate, Set<String> newTools, WeaveEnv env) {
        Path dir = script.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(dir, ".connect-", ".loom");
            Files.writeString(temporary, candidate);
            List<String> errors = new ArrayList<>();
            for (GateResult.Problem p : GenerateCommand.checker(env).errors(temporary, null))
                errors.add((p.line() > 0 ? "line " + p.line() + ": " : "") + p.message());
            if (!errors.isEmpty()) return new Verdict(errors, List.of(), false);
            LoomScript loaded = new LoomLoader().load(temporary.toString());
            List<Finding> mine = new ArrayList<>();
            for (Finding f : SecurityAudit.audit(loaded, script.getFileName().toString()).findings())
                if (newTools.stream().anyMatch(n -> f.where().equals("tool " + n) || f.where().equals("mcp " + n))) mine.add(f);
            boolean high = mine.stream().anyMatch(f -> f.severity() == Severity.HIGH);
            return new Verdict(List.of(), mine, high);
        } catch (Exception e) {
            return new Verdict(List.of("the candidate could not be checked: " + e.getMessage()), List.of(), false);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // a leftover temporary file is not a reason to fail the command
                }
            }
        }
    }

    /** What the audit says about each new declaration, as the audit words it. */
    static void printFindings(Verdict v, WeaveEnv env) {
        if (v.findings().isEmpty()) {
            env.out().println("The audit has nothing to say about the new declarations.");
            return;
        }
        env.out().println("The audit, on the new declarations:");
        for (Finding f : v.findings())
            env.out().println("  " + f.rule() + " " + f.severity() + "  " + f.where() + ": " + f.title());
    }

    /**
     * Shows the change, asks, and writes. Returns the exit code: 0 for written or declined, 1 for a
     * candidate that fails a gate or a file that cannot be written.
     *
     * @param yes whether the person already approved (the flag is the person's act)
     */
    static int apply(Path script, String before, String after, Set<String> newTools, boolean yes, WeaveEnv env) {
        Verdict verdict = gates(script, after, newTools, env);
        if (!verdict.passed()) {
            env.err().println("Error: the result does not pass weave check, so nothing was written:");
            verdict.errors().forEach(e -> env.err().println("  " + e));
            return 1;
        }
        env.out().println("The change to " + script.getFileName() + ":");
        env.out().print(McpScriptEditor.diff(before, after));
        printFindings(verdict, env);
        if (verdict.highFinding()) {
            env.err().println("Error: a new declaration has a high audit finding, so nothing was written; change the declaration or accept it in the script by hand.");
            return 1;
        }
        if (!yes) {
            String answer = env.human().promptHuman("Write this change, with the effects as proposed? (yes/no)");
            if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).matches("y|yes")) {
                env.out().println("Not written.");
                return 0;
            }
        }
        try {
            Files.writeString(script, after);
        } catch (IOException e) {
            env.err().println("Error: could not write " + script + ": " + e.getMessage());
            return 1;
        }
        env.out().println("Written.");
        return 0;
    }
}
