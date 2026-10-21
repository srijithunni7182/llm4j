package io.github.llm4j.loom.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.CliProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R20, R23 and the check side of R8: weave check with .loct and windows files. */
class LoctCheckTest {

    @TempDir Path dir;

    @AfterEach
    void clean() {
        ContextFiles.clear();
    }

    private String check(String block, String loct, String windows) throws Exception {
        Path f = dir.resolve("flow.loom");
        Files.writeString(
                f,
                "agent A {\n  model: \"gemini-2.5-flash\"\n  "
                        + block
                        + "\n}\nworkflow Main() { delegate \"x\" to A -> out }\n");
        if (loct != null) Files.writeString(dir.resolve("flow.loct"), loct);
        if (windows != null) Files.writeString(dir.resolve("flow.windows"), windows);
        ContextFiles.clear();
        ContextFiles.beside(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Map<String, String> env = Map.of("GEMINI_API_KEY", "g");
        CliProbe.check(
                f.toFile(),
                new PrintStream(out, true),
                env::get,
                new io.github.llm4j.loom.execution.DefaultLLMClientFactory(env::get));
        return out.toString();
    }

    private static final String PKG = "io.github.llm4j.loom.context.Strategies$";

    @Test
    void aNameFromALoctFileBesideTheScriptIsKnown() throws Exception {
        String out =
                check(
                        "context { compact: chain(keep_newest, drop_oldest) }",
                        "keep_newest = " + PKG + "KeepNewest\n",
                        null);
        assertThat(out).contains("ready to run").doesNotContain("unknown compaction strategy");
    }

    @Test
    void withoutTheMappingTheNameIsUnknownAndTheLoctNamesAreListed() throws Exception {
        String out =
                check(
                        "context { compact: keep_newst }",
                        "keep_newest = " + PKG + "KeepNewest\n",
                        null);
        assertThat(out)
                .contains("unknown compaction strategy 'keep_newst'")
                .contains("did you mean 'keep_newest'")
                .contains(".loct");
    }

    @Test
    void aShadowingNameIsAnErrorNamingTheFileAndLine() throws Exception {
        String out =
                check(
                        "context { compact: drop_oldest }",
                        "# mine\ndrop_oldest = " + PKG + "KeepNewest\n",
                        null);
        assertThat(out).contains("flow.loct").contains("line 2").contains("built-in");
    }

    @Test
    void aClassOfTheWrongTypeIsAnError() throws Exception {
        String out = check("context { compact: oops }", "oops = " + PKG + "NotAStrategy\n", null);
        assertThat(out).contains("neither ContextCompactor nor ContextCompactorProvider");
    }

    @Test
    void aClassThatIsNotOnTheClassPathIsOnlyAWarningAtCheckTime() throws Exception {
        String out = check("context { compact: later }", "later = com.nowhere.Gone\n", null);
        assertThat(out)
                .contains("not on the class path")
                .contains("warning")
                .contains("ready to run");
    }

    @Test
    void checkNeverRunsTheStrategy() throws Exception {
        Strategies.calls.set(0);
        check("context { compact: keep_newest }", "keep_newest = " + PKG + "KeepNewest\n", null);
        assertThat(Strategies.calls.get()).isZero();
    }

    @Test
    void aModelWithoutAKnownWindowGetsOneWarning() throws Exception {
        String out = check("context { compact: drop_oldest }", null, null);
        assertThat(out)
                .contains("no context window is known for gemini-2.5-flash")
                .contains("--context-windows");
        assertThat(out.split("no context window is known", -1)).hasSize(2);
    }

    @Test
    void aWindowsFileBesideTheScriptSilencesTheWarning() throws Exception {
        String out =
                check(
                        "context { compact: drop_oldest }",
                        null,
                        "gemini-2.5-flash = 1000000 / 8192\n");
        assertThat(out).doesNotContain("no context window").contains("ready to run");
    }

    @Test
    void noBlockNoWindowWarning() throws Exception {
        assertThat(check("", null, null)).doesNotContain("no context window");
    }

    @Test
    void listOfProblemsFromVerifyIsStable() {
        CompactorRegistry r = new CompactorRegistry();
        assertThat(r.verify()).isEqualTo(List.of());
    }
}
