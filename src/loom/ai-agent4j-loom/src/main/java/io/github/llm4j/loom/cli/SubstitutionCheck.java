package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.SubstitutionMigration;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code weave check --substitution braces} and {@code --fix-substitution}: what would change if a
 * script said {@code substitution: braces}, and the rewrite of the plain cases. The preview writes
 * nothing; the rewrite shows the changed lines first and writes only after a yes.
 */
final class SubstitutionCheck {

    private SubstitutionCheck() {}

    static int run(File scriptFile, String mode, boolean fix, boolean yes, WeaveEnv env) {
        if (mode != null && !mode.equals("braces")) {
            env.err()
                    .println(
                            "Error: --substitution takes braces (the mode to preview), not "
                                    + mode
                                    + ".");
            return 2;
        }
        Path script = scriptFile.toPath();
        String source;
        LoomScript loaded;
        try {
            source = Files.readString(script, StandardCharsets.UTF_8);
            loaded = new LoomLoader().load(script.toString());
        } catch (IOException | RuntimeException e) {
            env.err().println("Error: " + scriptFile + " could not be read: " + e.getMessage());
            return 2;
        }
        List<SubstitutionMigration.Change> changes = SubstitutionMigration.preview(loaded, source);
        if (changes.isEmpty()) {
            env.out()
                    .println(
                            "Nothing would change under substitution: braces: no variable is written as a bare word in text the runtime substitutes into.");
            return 0;
        }
        env.out()
                .println(
                        changes.size()
                                + " place"
                                + (changes.size() == 1 ? "" : "s")
                                + " would change under substitution: braces (a bare name stops being replaced):");
        for (SubstitutionMigration.Change c : changes)
            env.out()
                    .println(
                            "  line "
                                    + c.line()
                                    + ": "
                                    + c.position()
                                    + " has the word "
                                    + c.variable()
                                    + (c.standalone() ? "" : ", inside another word"));
        if (!fix) {
            env.out()
                    .println(
                            "Write {name} where you mean the variable, then add substitution: braces to the script. weave check "
                                    + scriptFile
                                    + " --fix-substitution does the plain cases.");
            return 0;
        }
        String rewritten = SubstitutionMigration.rewrite(source, changes);
        long left = changes.stream().filter(c -> !c.standalone()).count();
        if (rewritten.equals(source)) {
            env.out().println("Nothing is plainly a variable: " + left + " left for you.");
            return 0;
        }
        env.out().println();
        env.out().print(SubstitutionMigration.diff(source, rewritten));
        if (!yes) {
            String answer =
                    env.human().promptHuman("Write these changes to " + scriptFile + "? (yes/no)");
            if (answer == null || !answer.strip().equalsIgnoreCase("yes")) {
                env.out().println("Nothing was written.");
                return 0;
            }
        }
        try {
            Files.writeString(script, rewritten, StandardCharsets.UTF_8);
        } catch (IOException e) {
            env.err().println("Error: could not write " + scriptFile + ": " + e.getMessage());
            return 2;
        }
        env.out()
                .println(
                        "Wrote "
                                + scriptFile
                                + (left > 0
                                        ? "; "
                                                + left
                                                + " left for you (a word inside another word)."
                                        : "."));
        return 0;
    }
}
