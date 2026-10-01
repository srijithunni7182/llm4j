package io.github.llm4j.tools.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.tools.support.Declared;
import io.github.llm4j.tools.support.RecordingEffects;
import io.github.llm4j.tools.EffectJournal;
import io.github.llm4j.tools.ShellKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ShellToolTest {

    @TempDir
    Path dir;
    RecordingEffects ctx;
    Declared declared;

    @BeforeEach
    void setUp() {
        ctx = new RecordingEffects();
        declared = new Declared(Map.of(), dir);
    }

    Tool tool(String options) throws Exception {
        return declared.create("tool Ops { use: shell  " + options + " }", ctx);
    }

    static String run(Tool tool, Map<String, Object> args) {
        try {
            return tool.execute(args);
        } catch (Exception e) {
            throw new AssertionError("a generic tool threw instead of returning an Error: " + e, e);
        }
    }

    static Map<String, Object> call(String program, String... args) {
        return Map.of("program", program, "args", List.of(args));
    }

    // ── V8.1 no shell ────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.1")
    @Tag("H5")
    void argumentsAreNeverInterpretedByAShell() throws Exception {
        Files.writeString(dir.resolve("canary.txt"), "alive");
        String result = run(tool("allow: \"echo\""), call("echo", "a b", "$HOME", "; rm -rf /", "$(id)", "`id`", "*", "> canary.txt", "| cat"));

        assertThat(result).startsWith("exit 0\n").contains("a b $HOME ; rm -rf / $(id) `id` * > canary.txt | cat");
        assertThat(Files.readString(dir.resolve("canary.txt"))).isEqualTo("alive");
    }

    // ── V8.2 / V8.3 programs ─────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.2")
    @Tag("H5")
    void onlyAllowedProgramsByNameCanRun() throws Exception {
        Tool t = tool("allow: \"echo\"");
        for (String program : List.of("ls", "/bin/echo", "/usr/bin/echo", "../x", "./echo", "echo; ls", "sh", "")) {
            assertThat(run(t, call(program, "x"))).as(program).startsWith("Error:");
        }
        assertThat(run(t, Map.of("args", List.of("x")))).contains("program is required");
        assertThat(declared.problems("tool Ops { use: shell  allow: \"no-such-program-xyz\" }")).anyMatch(p -> p.contains("not found on the PATH"));
        assertThat(declared.problems("tool Ops { use: shell  allow: \"/bin/echo\" }")).anyMatch(p -> p.contains("not a path"));
        assertThat(declared.problems("tool Ops { use: shell  allow: \"echo ls\" }")).anyMatch(p -> p.contains("not a path or a command line"));
        assertThat(declared.problems("tool Ops { use: shell  allow: \" , \" }")).anyMatch(p -> p.contains("at least one program"));
        assertThat(declared.problems("tool Ops { use: shell }")).anyMatch(p -> p.contains("needs allow"));
    }

    @Test
    @Tag("V8.3")
    void interpretersAndWrappersAreLoadErrorsUnlessAllowed() {
        for (String name : List.of("sh", "bash", "python3", "python3.11", "python", "env", "xargs", "find", "sed", "awk", "sudo", "ssh", "node", "perl", "nohup", "timeout", "make")) {
            assertThat(declared.problems("tool Ops { use: shell  allow: \"" + name + "\" }")).as(name)
                    .anyMatch(p -> p.contains("can run any other program") && p.contains("allow_interpreters"));
        }
        assertThat(declared.problems("tool Ops { use: shell  allow: \"sh\"  allow_interpreters: true }")).isEmpty();
    }

    // ── V8.4 arguments ───────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.4")
    @Tag("H5")
    void unusualArgumentsAreRefused() throws Exception {
        Tool t = tool("allow: \"echo\"");
        assertThat(run(t, call("echo", "a\nb"))).contains("line breaks");
        assertThat(run(t, call("echo", "a\rb"))).startsWith("Error:");
        assertThat(run(t, call("echo", "a\u0000b"))).startsWith("Error:");
        assertThat(run(t, Map.of("program", "echo", "args", java.util.Collections.nCopies(65, "x")))).contains("too many arguments");
        assertThat(run(t, call("echo", "x".repeat(4097)))).contains("too long");
        assertThat(run(t, Map.of("program", "echo", "args", "a b c"))).contains("list of strings");
        assertThat(run(t, Map.of("program", "echo"))).startsWith("exit 0");
    }

    // ── V8.5 environment ─────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.5")
    void theChildSeesOnlyAMinimalEnvironment() throws Exception {
        assertThat(System.getenv("LOOM_TEST_MARKER")).as("the build gives the test JVM a marker variable").isEqualTo("marker-from-parent-9f3a");

        String env = run(tool("allow: \"printenv\""), call("printenv"));

        assertThat(env).contains("PATH=").contains("LANG=").contains("TZ=").doesNotContain("LOOM_TEST_MARKER").doesNotContain("marker-from-parent");
        assertThat(env.lines().filter(l -> l.matches("[A-Z_]+=.*")).count()).as("only PATH, LANG, TZ").isLessThanOrEqualTo(3);
    }

    @Test
    @Tag("V8.5")
    void passedVariablesReachTheChildButTheirValuesAreScrubbedFromWhatComesBack() throws Exception {
        String env = run(tool("allow: \"printenv\"  env_pass: \"LOOM_TEST_MARKER\""), call("printenv", "LOOM_TEST_MARKER"));
        assertThat(env).startsWith("exit 0").contains("***").doesNotContain("marker-from-parent-9f3a");
    }

    // ── V8.6 cwd ─────────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.6")
    void theProgramRunsInTheConfiguredDirectoryWhichMustBeInsideTheScriptDirectory() throws Exception {
        Files.createDirectories(dir.resolve("work"));
        String where = run(tool("allow: \"pwd\"  cwd: \"work\""), call("pwd"));
        assertThat(where).contains(dir.resolve("work").toRealPath().toString());

        assertThat(declared.problems("tool Ops { use: shell  allow: \"pwd\"  cwd: \"../elsewhere\" }")).anyMatch(p -> p.contains("cwd:"));
        assertThat(declared.problems("tool Ops { use: shell  allow: \"pwd\"  cwd: \"/etc\" }")).anyMatch(p -> p.contains("cwd:"));
        assertThat(declared.problems("tool Ops { use: shell  allow: \"pwd\"  cwd: \"missing\" }")).anyMatch(p -> p.contains("not a directory"));
    }

    // ── V8.7 timeout and process tree ────────────────────────────────────────────────────────

    @Test
    @Tag("V8.7")
    void aProgramThatRunsTooLongIsStoppedAndSoAreItsChildren() throws Exception {
        Tool t = tool("allow: \"sh\"  allow_interpreters: true  timeout: 1s  unattended: true");
        long t0 = System.nanoTime();

        String result = run(t, call("sh", "-c", "sleep 4242 & echo $! > child.pid; wait"));

        assertThat(result).startsWith("Error:").contains("ran longer than 1s");
        assertThat((System.nanoTime() - t0) / 1_000_000_000L).isLessThan(20);
        long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")).strip());
        long deadline = System.currentTimeMillis() + 5000;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("the grandchild was killed too").isFalse();
        assertThat(ctx.journal().all().values()).extracting(EffectJournal.Entry::kind).containsExactly("effect_failed");
    }

    // ── V8.8 output bounds ───────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.8")
    void aFloodOfOutputIsCutAndDoesNotBlockOrFillMemory() throws Exception {
        String result = run(tool("allow: \"head\"  max_output: 1k"), call("head", "-c", "50000000", "/dev/zero"));
        assertThat(result).startsWith("exit 0").contains("cut: first 1024 bytes shown");
        assertThat(result.length()).isLessThan(4000);
    }

    @Test
    @Tag("V8.8")
    @Tag("H5")
    void anEndlessProgramEndsAtTheTimeoutWithBoundedMemory() throws Exception {
        long before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        String result = run(tool("allow: \"yes\"  timeout: 1s  max_output: 4k"), call("yes"));
        long after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        assertThat(result).startsWith("Error:").contains("ran longer");
        assertThat(after - before).as("memory growth").isLessThan(100L * 1024 * 1024);
    }

    // ── V8.9 results ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.9")
    void theResultShowsTheExitCodeAndBothStreamsSeparately() throws Exception {
        Tool t = tool("allow: \"ls, true, false\"");
        assertThat(run(t, call("true"))).isEqualTo("exit 0\nstdout: (empty)\nstderr: (empty)\n");
        assertThat(run(t, call("false"))).startsWith("exit 1\n");
        String missing = run(t, call("ls", "/definitely/not/here"));
        assertThat(missing).startsWith("exit 2\n").contains("stdout: (empty)").contains("stderr:\n").contains("No such file");
        Files.writeString(dir.resolve("seen.txt"), "x");
        assertThat(run(t, call("ls", dir.toString()))).contains("stdout:\n").contains("seen.txt");
    }

    // ── V8.10 unattended ─────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.10")
    void unattendedMustBeABoolean() {
        assertThat(declared.problems("tool Ops { use: shell  allow: \"echo\"  unattended: sure }")).anyMatch(p -> p.contains("unattended"));
    }

    // ── V8.12 replay ─────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.12")
    void aResumedRunDoesNotRunTheProgramAgain() throws Exception {
        Tool first = tool("allow: \"mkdir\"");
        assertThat(run(first, call("mkdir", "made"))).startsWith("exit 0");
        // A repeat would fail with "File exists" (exit 1); a replay returns the recorded success.
        String resumed = run(tool("allow: \"mkdir\""), call("mkdir", "made"));
        assertThat(resumed).contains("already done").contains("exit 0");
    }

    // ── V8.13 platform ───────────────────────────────────────────────────────────────────────

    @Test
    @Tag("V8.13")
    void windowsIsRefusedAtLoad() {
        Declared onWindows = new Declared(Map.of(), dir, new ShellKind("Windows 11"));
        assertThat(onWindows.problems("tool Ops { use: shell  allow: \"echo\" }")).anyMatch(p -> p.contains("Windows"));
        Declared onMac = new Declared(Map.of(), dir, new ShellKind("Mac OS X"));
        assertThat(onMac.problems("tool Ops { use: shell  allow: \"echo\" }")).isEmpty();
    }

    // ── H5 hostile ───────────────────────────────────────────────────────────────────────────

    @Test
    @Tag("H5")
    void hostileCallsChangeNothing() throws Exception {
        Files.writeString(dir.resolve("canary.txt"), "alive");
        Tool t = tool("allow: \"echo\"");
        List<Map<String, Object>> attacks = List.of(
                call("/bin/sh", "-c", "rm canary.txt"), call("sh", "-c", "rm canary.txt"), call("../x"), call("echo; rm canary.txt"),
                call("echo", "a\nrm canary.txt"), Map.of("program", "echo", "args", "a; rm canary.txt"),
                Map.of("program", List.of("echo")), Map.of("cmd", "rm canary.txt"));
        for (Map<String, Object> attack : attacks) run(t, attack);

        assertThat(Files.readString(dir.resolve("canary.txt"))).isEqualTo("alive");
        assertThat(IOExceptionFree.listed(dir)).containsExactlyInAnyOrder("canary.txt");
    }

    static final class IOExceptionFree {
        static List<String> listed(Path dir) throws IOException {
            try (var s = Files.list(dir)) {
                return s.map(p -> p.getFileName().toString()).toList();
            }
        }
    }
}
