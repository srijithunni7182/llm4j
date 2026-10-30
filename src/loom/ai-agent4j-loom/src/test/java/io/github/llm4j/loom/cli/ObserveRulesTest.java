package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 22 R8: {@code weave observe rules} works without ceremony. No rules file, or one with
 * nothing in it, means the defaults apply; a file with rules and no add-on says how to get the
 * add-on; {@code --init} writes a commented starter.
 */
class ObserveRulesTest {

    @TempDir Path dir;

    private record Out(int code, String out, String err) {}

    private Path script() throws Exception {
        return Files.writeString(dir.resolve("flow.loom"), "workflow W() {\n return \"x\"\n}\n");
    }

    private Out weave(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            return new Out(WeaveCLI.commandLine().execute(args), out.toString(), err.toString());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void noRulesFileMeansTheDefaultsApplyNotAnError() throws Exception {
        Out r = weave("observe", "rules", script().toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("flow.inspect").contains("defaults apply").contains("--init");
    }

    @Test
    void anEmptyOrCommentOnlyFileMeansNoRulesToo() throws Exception {
        Path s = script();
        Files.writeString(dir.resolve("flow.inspect"), "\n# nothing yet\n// or here\n");

        Out r = weave("observe", "rules", s.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("has no rules").contains("defaults apply");
    }

    @Test
    void aFileWithRulesAndNoAddOnSaysWhatItIsAndHowToGetIt() throws Exception {
        Path s = script();
        Files.writeString(dir.resolve("flow.inspect"), "* { paused_longer_than: 90s }\n");

        Out r = weave("observe", "rules", s.toString());

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("inspect add-on is not on the class path").contains("weave tools fetch inspect").contains("--classes");
    }

    @Test
    void initWritesACommentedStarterBesideTheScriptAndNeverOverwrites() throws Exception {
        Path s = script();

        Out first = weave("observe", "rules", s.toString(), "--init");
        assertThat(first.code()).isZero();
        String starter = Files.readString(dir.resolve("flow.inspect"));
        assertThat(starter.lines().filter(l -> !l.isBlank())).allMatch(l -> l.startsWith("#"));
        assertThat(starter).contains("context_nearly_full").contains("same_action");
        assertThat(first.out()).contains("flow.inspect");

        Files.writeString(dir.resolve("flow.inspect"), "mine\n");
        Out second = weave("observe", "rules", s.toString(), "--init");
        assertThat(second.code()).isEqualTo(2);
        assertThat(second.err()).contains("already exists");
        assertThat(Files.readString(dir.resolve("flow.inspect"))).isEqualTo("mine\n");
    }

    @Test
    void theStarterIsAllCommentsSoItChangesNothingUntilALineIsUncommented() throws Exception {
        Path s = script();
        weave("observe", "rules", s.toString(), "--init");

        Out r = weave("observe", "rules", s.toString());

        assertThat(r.code()).isZero();
        assertThat(r.out()).contains("has no rules");
    }
}
