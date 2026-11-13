package io.github.llm4j.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectJournal;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.WorkspaceKind;
import io.github.llm4j.tools.support.FaultJournal;
import io.github.llm4j.tools.support.RecordingEffects;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 35, D11 and G54 to G58: the {@code workspace} kind. */
class WorkspaceToolTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    Path ws;
    EffectJournal journal = EffectJournal.inMemory();
    RecordingEffects effects = new RecordingEffects(journal);

    static final Map<String, String> RW =
            Map.of(
                    "root",
                    "ws",
                    "allow",
                    "**",
                    "mode",
                    "readwrite",
                    "overwrite",
                    "true",
                    "store",
                    "snaps");

    @BeforeEach
    void workspace() throws Exception {
        ws = Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/report.md"), "# Report\nfirst draft\n");
        Files.writeString(ws.resolve("notes.txt"), "keep\n");
    }

    Tool tool(Map<String, String> options) throws Exception {
        return new WorkspaceKind().create("Data", options, dir, effects);
    }

    int steps;

    /** One call as its own step of a run, as each use statement is. */
    JsonNode call(Map<String, String> options, Object... args) throws Exception {
        effects.step = "main/s" + (++steps);
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = tool(options).execute(a);
        return out.startsWith("Error:") || out.startsWith("(")
                ? JSON.getNodeFactory().textNode(out)
                : JSON.readTree(out);
    }

    JsonNode call(Object... args) throws Exception {
        return call(RW, args);
    }

    static Map<String, Object> edit(String old, String now) {
        Map<String, Object> e = new HashMap<>();
        e.put("old", old);
        e.put("new", now);
        return e;
    }

    // ---- the kind

    @Test
    void itReachesPrivateDataAndChangesItUnlessReadOnly() {
        ToolProfile rw =
                new WorkspaceKind()
                        .profile(
                                Map.of(
                                        "root",
                                        "ws",
                                        "allow",
                                        "**",
                                        "mode",
                                        "readwrite",
                                        "protect",
                                        "tests/**"));
        assertThat(rw.privateData()).isTrue();
        assertThat(rw.outward() || rw.untrusted()).isFalse();
        assertThat(rw.effect()).isEqualTo(TaskEffect.CHANGES);
        assertThat(rw.journaled()).isTrue();
        assertThat(rw.note()).contains("ws").contains("tests/**");
        assertThat(new WorkspaceKind().profile(Map.of("root", "ws", "allow", "**")).effect())
                .isEqualTo(TaskEffect.READS);
        assertThat(new WorkspaceKind().uncheckedResult(Map.of()))
                .contains("content", "matches", "paths");
    }

    @Test
    void allowIsRequiredAndOptionsAreChecked() {
        assertThat(new WorkspaceKind().check(Map.of("root", "ws"), dir)).contains("allow");
        assertThat(
                        new WorkspaceKind()
                                .check(
                                        Map.of("root", "ws", "allow", "**", "store", "ws/snaps"),
                                        dir))
                .contains("store");
        assertThat(new WorkspaceKind().check(Map.of("root", "../out", "allow", "**"), dir))
                .contains("root");
        assertThat(
                        new WorkspaceKind()
                                .check(Map.of("root", "ws", "allow", "**", "snapshots", "99"), dir))
                .contains("snapshots");
        assertThat(new WorkspaceKind().check(RW, dir)).isNull();
    }

    // ---- reading and writing

    @Test
    void readReturnsTheTextAndAVersion() throws Exception {
        JsonNode r = call("action", "read", "path", "docs/report.md");
        assertThat(r.get("content").asText()).isEqualTo("# Report\nfirst draft\n");
        assertThat(r.get("version").asText()).hasSize(12);
        JsonNode lines =
                call("action", "read", "path", "docs/report.md", "from_line", 2, "lines", 1);
        assertThat(lines.get("content").asText()).isEqualTo("first draft");
        Files.write(ws.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0});
        assertThat(call("action", "read", "path", "logo.png").asText())
                .startsWith("Error:")
                .contains("not text")
                .contains("6 bytes");
    }

    @Test
    void writeAppendExistsAndList() throws Exception {
        JsonNode w = call("action", "write", "path", "out/summary.md", "content", "Short.\n");
        assertThat(w.get("version").asText()).hasSize(12);
        assertThat(Files.readString(ws.resolve("out/summary.md"))).isEqualTo("Short.\n");
        call("action", "append", "path", "out/summary.md", "content", "More.");
        assertThat(Files.readString(ws.resolve("out/summary.md"))).isEqualTo("Short.\nMore.\n");
        assertThat(call("action", "exists", "path", "out/summary.md").get("exists").asBoolean())
                .isTrue();
        assertThat(call("action", "exists", "path", "nope.md").get("exists").asBoolean()).isFalse();
        assertThat(call("action", "list").get("entries").toString())
                .contains("docs/", "notes.txt", "out/");
        Map<String, String> noOverwrite = new HashMap<>(RW);
        noOverwrite.put("overwrite", "false");
        assertThat(
                        call(noOverwrite, "action", "write", "path", "notes.txt", "content", "x")
                                .asText())
                .startsWith("Error:")
                .contains("already exists");
    }

    @Test
    void modesLimitTheActions() throws Exception {
        Map<String, String> read = Map.of("root", "ws", "allow", "**", "store", "snaps");
        assertThat(call(read, "action", "write", "path", "a.md", "content", "x").asText())
                .startsWith("Error:")
                .contains("read only");
        assertThat(call(read, "action", "snapshot", "name", "s").asText()).contains("read only");
        assertThat(call(read, "action", "read", "path", "notes.txt").get("content").asText())
                .isEqualTo("keep\n");
        assertThat(call(read, "action", "erase", "path", "notes.txt").asText()).contains("action");
    }

    @Test
    void onlyAllowedPathsAreUsed() throws Exception {
        Map<String, String> md = new HashMap<>(RW);
        md.put("allow", "*.md");
        assertThat(call(md, "action", "read", "path", "docs/report.md").get("content").asText())
                .contains("Report");
        assertThat(call(md, "action", "read", "path", "notes.txt").asText())
                .startsWith("Error:")
                .contains("allow");
        md.put("allow", "docs/**");
        assertThat(
                        call(md, "action", "write", "path", "docs/x/y.md", "content", "x")
                                .get("version"))
                .isNotNull();
        assertThat(call(md, "action", "write", "path", "top.md", "content", "x").asText())
                .contains("allow");
    }

    // ---- G54: a jail that follows no link

    @Test
    void aLinkIsNeverFollowed() throws Exception {
        Files.writeString(dir.resolve("secret.md"), "outside-canary");
        Files.createSymbolicLink(ws.resolve("link.md"), dir.resolve("secret.md"));
        Files.createDirectories(dir.resolve("elsewhere"));
        Files.writeString(dir.resolve("elsewhere/x.md"), "outside-canary");
        Files.createSymbolicLink(ws.resolve("linkdir"), dir.resolve("elsewhere"));

        assertThat(call("action", "read", "path", "link.md").asText())
                .startsWith("Error:")
                .contains("link");
        assertThat(call("action", "read", "path", "linkdir/x.md").asText())
                .startsWith("Error:")
                .contains("link");
        assertThat(call("action", "write", "path", "link.md", "content", "x").asText())
                .contains("link");
        assertThat(call("action", "write", "path", "linkdir/new.md", "content", "x").asText())
                .contains("link");
        assertThat(Files.exists(dir.resolve("elsewhere/new.md"))).isFalse();
        assertThat(call("action", "find", "pattern", "**/*.md").toString()).doesNotContain("link");
        assertThat(call("action", "grep", "text", "outside-canary").get("matches").size()).isZero();
        assertThat(call("action", "snapshot", "name", "s").asText())
                .startsWith("Error:")
                .contains("link.md");
        assertThat(call("action", "read", "path", "../secret.md").asText()).startsWith("Error:");
        assertThat(effects.everything()).doesNotContain("outside-canary");
    }

    @Test
    void aLinkMadeAfterASnapshotIsRemovedByRestoreNotFollowed() throws Exception {
        call("action", "snapshot", "name", "clean");
        Files.writeString(dir.resolve("victim.md"), "untouched");
        Files.createSymbolicLink(ws.resolve("docs/report.md.tmp"), dir.resolve("victim.md"));
        Files.delete(ws.resolve("docs/report.md"));
        Files.createSymbolicLink(ws.resolve("docs/report.md"), dir.resolve("victim.md"));
        call("action", "restore", "name", "clean");
        assertThat(Files.isSymbolicLink(ws.resolve("docs/report.md"))).isFalse();
        assertThat(Files.readString(ws.resolve("docs/report.md")))
                .isEqualTo("# Report\nfirst draft\n");
        assertThat(
                        Files.exists(
                                ws.resolve("docs/report.md.tmp"),
                                java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .isFalse();
        assertThat(Files.readString(dir.resolve("victim.md"))).isEqualTo("untouched");
    }

    // ---- G55: the deny list allow cannot override

    @Test
    void theBuiltInDenyListWinsOverAllow() throws Exception {
        Files.createDirectories(ws.resolve(".git/hooks"));
        assertThat(
                        call(
                                        "action",
                                        "write",
                                        "path",
                                        ".git/hooks/pre-commit",
                                        "content",
                                        "rm -rf /")
                                .asText())
                .startsWith("Error:")
                .contains(".git");
        assertThat(Files.exists(ws.resolve(".git/hooks/pre-commit"))).isFalse();
        for (String secret :
                List.of(
                        ".env",
                        ".env.local",
                        "deploy/x.pem",
                        "x.key",
                        "id_rsa",
                        "keys/id_ed25519")) {
            Path p = ws.resolve(secret);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "deny-canary");
            assertThat(call("action", "read", "path", secret).asText())
                    .as(secret)
                    .startsWith("Error:")
                    .contains("not readable");
        }
        assertThat(call("action", "grep", "text", "deny-canary").get("matches").size()).isZero();
        assertThat(
                        call(
                                        "action",
                                        "write",
                                        "path",
                                        ".github/workflows/ci.yml",
                                        "content",
                                        "on: push\n")
                                .get("version"))
                .isNotNull();
    }

    // ---- G56: a patch applies exactly or not at all

    @Test
    void aPatchAppliesEveryEditAgainstTheOriginal() throws Exception {
        Files.writeString(ws.resolve("a.md"), "alpha beta gamma\n");
        JsonNode r =
                call(
                        "action",
                        "patch",
                        "path",
                        "a.md",
                        "edits",
                        List.of(edit("alpha", "ALPHA beta"), edit("gamma", "G")));
        assertThat(Files.readString(ws.resolve("a.md"))).isEqualTo("ALPHA beta beta G\n");
        assertThat(r.get("edits").asInt()).isEqualTo(2);
        assertThat(r.get("version").asText())
                .isNotEqualTo(call("action", "read", "path", "notes.txt").get("version").asText());
    }

    @Test
    void aPatchThatCannotApplyChangesNothing() throws Exception {
        Files.writeString(ws.resolve("a.md"), "one two two three\n");
        String version = call("action", "read", "path", "a.md").get("version").asText();
        assertThat(
                        call("action", "patch", "path", "a.md", "edits", List.of(edit("two", "2")))
                                .asText())
                .contains("edit 1")
                .contains("found 2 times");
        assertThat(
                        call(
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "edits",
                                        List.of(edit("one", "1"), edit("four", "4")))
                                .asText())
                .contains("edit 2")
                .contains("not found");
        assertThat(
                        call(
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "edits",
                                        List.of(edit("one two", "x"), edit("two two", "y")))
                                .asText())
                .contains("overlap");
        assertThat(
                        call(
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "version",
                                        "000000000000",
                                        "edits",
                                        List.of(edit("one", "1")))
                                .asText())
                .contains("version");
        assertThat(call("action", "patch", "path", "a.md", "edits", List.of()).asText())
                .contains("edits");
        assertThat(
                        call("action", "patch", "path", "a.md", "edits", List.of(edit("", "x")))
                                .asText())
                .contains("empty");
        assertThat(
                        call(
                                        "action",
                                        "patch",
                                        "path",
                                        "missing.md",
                                        "edits",
                                        List.of(edit("a", "b")))
                                .asText())
                .startsWith("Error:");
        assertThat(Files.readString(ws.resolve("a.md"))).isEqualTo("one two two three\n");
        assertThat(
                        call(
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "version",
                                        version,
                                        "edits",
                                        List.of(edit("one", "1")))
                                .get("edits")
                                .asInt())
                .isEqualTo(1);
    }

    @Test
    void protectedPathsAreReadOnly() throws Exception {
        Files.createDirectories(ws.resolve("tests"));
        Files.writeString(ws.resolve("tests/check.md"), "expected: 3\n");
        Map<String, String> guarded = new HashMap<>(RW);
        guarded.put("protect", "tests/**");
        assertThat(
                        call(guarded, "action", "read", "path", "tests/check.md")
                                .get("content")
                                .asText())
                .contains("3");
        assertThat(
                        call(
                                        guarded,
                                        "action",
                                        "write",
                                        "path",
                                        "tests/check.md",
                                        "content",
                                        "expected: 4")
                                .asText())
                .contains("protected");
        assertThat(
                        call(guarded, "action", "append", "path", "tests/new.md", "content", "x")
                                .asText())
                .contains("protected");
        assertThat(
                        call(
                                        guarded,
                                        "action",
                                        "patch",
                                        "path",
                                        "tests/check.md",
                                        "edits",
                                        List.of(edit("3", "4")))
                                .asText())
                .contains("protected");
        assertThat(Files.readString(ws.resolve("tests/check.md"))).isEqualTo("expected: 3\n");
    }

    // ---- find, grep, diff

    @Test
    void findAndGrepAreBoundedAndSkipIgnoredPaths() throws Exception {
        Files.createDirectories(ws.resolve("node_modules/pkg"));
        Files.writeString(ws.resolve("node_modules/pkg/x.md"), "needle");
        Files.writeString(ws.resolve("docs/b.md"), "a needle\nand a needle again\nno\n");
        assertThat(call("action", "find", "pattern", "**/*.md").get("paths").toString())
                .contains("docs/report.md", "docs/b.md")
                .doesNotContain("node_modules");
        JsonNode g = call("action", "grep", "text", "needle");
        assertThat(g.get("matches").size()).isEqualTo(2);
        assertThat(g.get("matches").get(0).get("path").asText()).isEqualTo("docs/b.md");
        assertThat(g.get("matches").get(0).get("line").asInt()).isEqualTo(1);
        JsonNode ctx = call("action", "grep", "text", "again", "context", 1);
        assertThat(ctx.get("matches").get(0).get("before").toString()).contains("a needle");
        assertThat(ctx.get("matches").get(0).get("after").toString()).contains("no");
        JsonNode re = call("action", "grep", "regex", "ne+dle\\b", "path", "docs/*.md");
        assertThat(re.get("matches").size()).isEqualTo(2);
        assertThat(call("action", "grep", "regex", "(a").asText())
                .startsWith("Error:")
                .contains("regex");
        assertThat(call("action", "grep", "regex", "x".repeat(300)).asText())
                .contains("longer than");
        Map<String, String> few = new HashMap<>(RW);
        few.put("max_matches", "1");
        JsonNode capped = call(few, "action", "grep", "text", "needle");
        assertThat(capped.get("matches").size()).isEqualTo(1);
        assertThat(capped.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void aCatastrophicRegexStopsInsteadOfHanging() throws Exception {
        Files.writeString(ws.resolve("slow.md"), "a".repeat(40) + "!");
        long started = System.nanoTime();
        // the JDK guards simple nested repeats such as (a+)+; this one still backtracks for seconds
        JsonNode r = call("action", "grep", "regex", "(.*a){12}$");
        assertThat(System.nanoTime() - started).isLessThan(5_000_000_000L);
        assertThat(r.toString()).contains("too long");
    }

    @Test
    void diffListsWhatChangedSinceASnapshot() throws Exception {
        call("action", "snapshot", "name", "before");
        Files.writeString(ws.resolve("notes.txt"), "changed\n");
        Files.writeString(ws.resolve("new.md"), "new\n");
        Files.delete(ws.resolve("docs/report.md"));
        JsonNode d = call("action", "diff");
        assertThat(d.get("snapshot").asText()).isEqualTo("before");
        assertThat(d.get("added").toString()).contains("new.md");
        assertThat(d.get("changed").toString()).contains("notes.txt");
        assertThat(d.get("deleted").toString()).contains("docs/report.md");
        assertThat(call("action", "diff", "name", "missing").asText()).contains("no snapshot");
    }

    // ---- G57: restore is exact, idempotent and bounded

    @Test
    void restorePutsBackExactlyAndTwiceIsTheSame() throws Exception {
        Files.createDirectories(ws.resolve("tests"));
        Files.writeString(ws.resolve("tests/check.md"), "protected\n");
        Files.createDirectories(ws.resolve("target"));
        Files.writeString(ws.resolve("target/build.log"), "ignored\n");
        Map<String, String> o = new HashMap<>(RW);
        o.put("protect", "tests/**");
        call(o, "action", "snapshot", "name", "clean");

        Files.writeString(ws.resolve("docs/report.md"), "ruined\n");
        Files.delete(ws.resolve("notes.txt"));
        Files.createDirectories(ws.resolve("scratch/deep"));
        Files.writeString(ws.resolve("scratch/deep/tmp.md"), "made later\n");
        Files.writeString(ws.resolve("target/build.log"), "ignored, changed\n");
        Files.writeString(ws.resolve("tests/check.md"), "changed by a person\n");

        JsonNode r = call(o, "action", "restore", "name", "clean");
        assertThat(r.get("restored").asInt()).isEqualTo(2);
        assertThat(r.get("deleted").asInt()).isEqualTo(1);
        assertThat(Files.readString(ws.resolve("docs/report.md")))
                .isEqualTo("# Report\nfirst draft\n");
        assertThat(Files.readString(ws.resolve("notes.txt"))).isEqualTo("keep\n");
        assertThat(Files.exists(ws.resolve("scratch"))).isFalse();
        assertThat(Files.readString(ws.resolve("target/build.log")))
                .isEqualTo("ignored, changed\n");
        assertThat(Files.readString(ws.resolve("tests/check.md")))
                .isEqualTo("changed by a person\n");

        JsonNode again = call(o, "action", "restore", "name", "clean");
        assertThat(again.get("restored").asInt()).isZero();
        assertThat(again.get("deleted").asInt()).isZero();
        assertThat(Files.readString(ws.resolve("docs/report.md")))
                .isEqualTo("# Report\nfirst draft\n");
    }

    @Test
    void aWorkspaceOverTheCapTakesNoSnapshotAtAll() throws Exception {
        Map<String, String> small = new HashMap<>(RW);
        small.put("max_files", "1");
        assertThat(call(small, "action", "snapshot", "name", "s").asText())
                .startsWith("Error:")
                .contains("larger than the snapshot cap")
                .contains("ignore a path or raise the cap");
        assertThat(call("action", "diff", "name", "s").asText()).contains("no snapshot");
        small.put("max_files", "2");
        small.put("max_snapshot_bytes", "10");
        assertThat(call(small, "action", "snapshot", "name", "s").asText())
                .contains("larger than the snapshot cap");
    }

    @Test
    void aStoredFileThatChangedFailsTheRestoreAndNamesIt() throws Exception {
        call("action", "snapshot", "name", "clean");
        Files.writeString(ws.resolve("notes.txt"), "edited\n");
        try (var objects = Files.walk(dir.resolve("snaps"))) {
            for (Path p :
                    objects.filter(Files::isRegularFile)
                            .filter(p -> p.toString().contains("objects"))
                            .toList()) Files.writeString(p, "tampered");
        }
        assertThat(call("action", "restore", "name", "clean").asText())
                .startsWith("Error:")
                .contains("changed since it was stored");
        assertThat(Files.readString(ws.resolve("notes.txt"))).isEqualTo("edited\n");
    }

    @Test
    void snapshotsAreCountedAndANameIsReplaced() throws Exception {
        Map<String, String> two = new HashMap<>(RW);
        two.put("snapshots", "2");
        call(two, "action", "snapshot", "name", "a");
        call(two, "action", "snapshot", "name", "b");
        assertThat(call(two, "action", "snapshot", "name", "c").asText()).contains("2 snapshots");
        Files.writeString(ws.resolve("notes.txt"), "second\n");
        assertThat(call(two, "action", "snapshot", "name", "a").get("files").asInt()).isEqualTo(2);
        Files.writeString(ws.resolve("notes.txt"), "third\n");
        call(two, "action", "restore", "name", "a");
        assertThat(Files.readString(ws.resolve("notes.txt"))).isEqualTo("second\n");
        assertThat(call("action", "restore", "name", "zzz").asText()).contains("no snapshot");
        assertThat(call("action", "snapshot", "name", "../x").asText()).contains("name");
    }

    // ---- G58: journaled, and not redone on resume

    @Test
    void aResumedRunReusesTheRecordedSnapshot() throws Exception {
        Tool first = tool(RW);
        first.execute(Map.of("action", "snapshot", "name", "clean"));
        Files.writeString(ws.resolve("notes.txt"), "later state\n");
        // the same step runs again after a restart: the snapshot is not taken of the later state
        String again = tool(RW).execute(Map.of("action", "snapshot", "name", "clean"));
        assertThat(again).startsWith("(already done");
        tool(RW).execute(Map.of("action", "restore", "name", "clean"));
        assertThat(Files.readString(ws.resolve("notes.txt"))).isEqualTo("keep\n");
    }

    @Test
    void aRestoreCutShortByACrashIsRetriedOnResume() throws Exception {
        tool(RW).execute(Map.of("action", "snapshot", "name", "clean"));
        Files.writeString(ws.resolve("notes.txt"), "dirty\n");
        effects.step = "main/s1";
        Tool crashing = new WorkspaceKind().create("Data", RW, dir, crashAfterActing(effects.step));
        assertThatThrownBy(() -> crashing.execute(Map.of("action", "restore", "name", "clean")))
                .isInstanceOf(FaultJournal.SimulatedCrash.class);
        Files.writeString(ws.resolve("notes.txt"), "dirty again\n");
        String resumed = tool(RW).execute(Map.of("action", "restore", "name", "clean"));
        assertThat(resumed).doesNotStartWith("Error:");
        assertThat(Files.readString(ws.resolve("notes.txt"))).isEqualTo("keep\n");
    }

    /**
     * A context on the same journal that crashes when the call's outcome would be recorded: the run
     * id is already there, so the only write before acting is "pending".
     */
    RecordingEffects crashAfterActing(String step) {
        RecordingEffects r = new RecordingEffects(new FaultJournal(journal, 1, false));
        r.step = step;
        return r;
    }
}
