package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 22 R5.3 through the command: a preview, and a rewrite that needs a yes. */
class CheckSubstitutionCommandTest {

    @TempDir Path dir;

    private static final String SOURCE =
            "agent A { model: \"gemini-2.5-flash\" }\n"
                    + "workflow W(topic) {\n"
                    + "    delegate \"Write about topic for a general reader\" to A -> draft\n"
                    + "    note \"length-topic-of-range checked\"\n"
                    + "    return draft\n"
                    + "}\n";

    private record Out(int code, String out) {}

    private Out check(String stdin, String... extra) throws Exception {
        Path script = dir.resolve("main.loom");
        if (!Files.exists(script)) Files.writeString(script, SOURCE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        java.io.InputStream oldIn = System.in;
        System.setOut(new PrintStream(out, true));
        System.setIn(new ByteArrayInputStream(stdin.getBytes()));
        try {
            String[] args = new String[extra.length + 3];
            args[0] = "check";
            args[1] = script.toString();
            args[2] = "--no-env";
            System.arraycopy(extra, 0, args, 3, extra.length);
            return new Out(WeaveCLI.commandLine().execute(args), out.toString());
        } finally {
            System.setOut(oldOut);
            System.setIn(oldIn);
        }
    }

    @Test
    void thePreviewListsEachChangeAndWritesNothing() throws Exception {
        Out r = check("", "--substitution", "braces");

        assertThat(r.code()).isZero();
        assertThat(r.out())
                .contains("line 3")
                .contains("topic")
                .contains("delegate payload")
                .contains("line 4")
                .contains("inside another word");
        assertThat(Files.readString(dir.resolve("main.loom"))).isEqualTo(SOURCE);
    }

    @Test
    void aRewriteShowsTheDiffAndChangesNothingWithoutAYes() throws Exception {
        Out r = check("no\n", "--fix-substitution");

        assertThat(r.out()).contains("- ").contains("+ ").contains("{topic}");
        assertThat(Files.readString(dir.resolve("main.loom"))).isEqualTo(SOURCE);
    }

    @Test
    void aYesWritesTheRewriteAndLeavesTheAmbiguousCaseForThePerson() throws Exception {
        Out r = check("yes\n", "--fix-substitution");

        String after = Files.readString(dir.resolve("main.loom"));
        assertThat(after).contains("Write about {topic} for a general reader");
        assertThat(after).contains("length-topic-of-range checked");
        assertThat(r.out()).contains("left for you");
    }

    @Test
    void yesOnTheCommandLineSkipsTheQuestionForABuild() throws Exception {
        Out r = check("", "--fix-substitution", "--yes");

        assertThat(Files.readString(dir.resolve("main.loom"))).contains("Write about {topic} for");
        assertThat(r.code()).isZero();
    }

    @Test
    void anUnknownModeIsRefusedNamingTheOne() throws Exception {
        Out r = check("", "--substitution", "curly");

        assertThat(r.code()).isEqualTo(2);
    }
}
