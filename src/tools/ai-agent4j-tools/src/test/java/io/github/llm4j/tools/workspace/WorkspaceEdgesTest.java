package io.github.llm4j.tools.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectPolicy;
import io.github.llm4j.agent.tool.Effectful;
import io.github.llm4j.tools.EffectTool;
import io.github.llm4j.tools.WorkspaceKind;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The exact edges of {@code workspace}: modes, policies, limits, caps and what a model is told. */
class WorkspaceEdgesTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    Path ws;
    RecordingEffects effects = new RecordingEffects();
    int steps;

    @BeforeEach
    void workspace() throws Exception {
        ws = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(ws.resolve("a.md"), "one\ntwo\nthree\n");
    }

    Map<String, String> options(String... pairs) {
        Map<String, String> o =
                new HashMap<>(
                        Map.of("root", "ws", "allow", "**", "mode", "readwrite", "store", "snaps"));
        for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]);
        return o;
    }

    Tool tool(Map<String, String> o) throws Exception {
        return new WorkspaceKind().create("Data", o, dir, effects);
    }

    JsonNode call(Map<String, String> o, Object... args) throws Exception {
        effects.step = "main/s" + (++steps);
        Map<String, Object> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) a.put((String) args[i], args[i + 1]);
        String out = tool(o).execute(a);
        return out.startsWith("Error:") ? JSON.getNodeFactory().textNode(out) : JSON.readTree(out);
    }

    static Effectful inner(Tool t) {
        return ((EffectTool) t).effectful();
    }

    @ParameterizedTest
    @CsvSource({
        "read,read,true",
        "list,read,true",
        "exists,read,true",
        "find,read,true",
        "grep,read,true",
        "diff,read,true",
        "write,read,false",
        "append,read,false",
        "patch,read,false",
        "snapshot,read,false",
        "restore,read,false",
        "write,write,true",
        "append,write,true",
        "patch,write,true",
        "exists,write,true",
        "snapshot,write,true",
        "restore,write,true",
        "read,write,false",
        "list,write,false",
        "find,write,false",
        "grep,write,false",
        "diff,write,false",
        "grep,readwrite,true",
        "restore,readwrite,true"
    })
    void eachModeAllowsItsActions(String action, String mode, boolean allowed) throws Exception {
        JsonNode r =
                call(
                        options("mode", mode),
                        "action",
                        action,
                        "path",
                        "a.md",
                        "name",
                        "s",
                        "pattern",
                        "*.md",
                        "text",
                        "one",
                        "content",
                        "x",
                        "edits",
                        List.of(Map.of("old", "one", "new", "1")));
        boolean refusedForMode = r.isTextual() && r.asText().contains("is not available here");
        assertThat(refusedForMode).as(action + " in " + mode + ": " + r).isEqualTo(!allowed);
    }

    @Test
    void writesAreEffectsAndOnlySnapshotsAndRestoresAreSafeToRepeat() throws Exception {
        Effectful t = inner(tool(options("on_unknown", "retry")));
        for (String a : List.of("write", "append", "patch", "snapshot", "restore"))
            assertThat(t.isEffect(Map.of("action", a))).as(a).isTrue();
        for (String a : List.of("read", "list", "exists", "find", "grep", "diff", "nonsense"))
            assertThat(t.isEffect(Map.of("action", a))).as(a).isFalse();
        assertThat(t.isEffect(Map.of())).isFalse();
        assertThat(t.policy(Map.of("action", "restore")).idempotent()).isTrue();
        assertThat(t.policy(Map.of("action", "snapshot")).idempotent()).isTrue();
        assertThat(t.policy(Map.of("action", "append")).idempotent()).isFalse();
        assertThat(t.policy(Map.of("action", "append")).onUnknown())
                .isEqualTo(EffectPolicy.OnUnknown.RETRY);
        assertThat(t.policy(Map.of()).idempotent()).isFalse();
        assertThat(t.target(Map.of("action", "write", "path", "a.md"))).isEqualTo("write a.md");
        assertThat(t.target(Map.of("action", "restore", "name", "clean")))
                .isEqualTo("restore clean");
        assertThat(t.target(Map.of("action", "list"))).isEqualTo("list");
    }

    @Test
    void theModelIsToldTheActionsOfItsModeAndWhatItMayTouch() throws Exception {
        String read = tool(options("mode", "read", "allow", "docs/**")).getDescription();
        assertThat(read)
                .contains("Actions: read, list, exists, find, grep, diff.")
                .contains("docs/**")
                .doesNotContain("read only");
        String guarded = tool(options("protect", "tests/**")).getDescription();
        assertThat(guarded).contains("snapshot, restore").contains("tests/** are read only");
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
                (Map<String, Object>) tool(options()).getParametersSchema().get("properties");
        assertThat(props).containsKeys("action", "path", "edits", "name", "regex");
        assertThat(props.get("edits").toString()).contains("old").contains("new");
    }

    @Test
    void sizesAreBoundedExactly() throws Exception {
        Map<String, String> small = options("max_bytes", "10", "overwrite", "true");
        assertThat(
                        call(small, "action", "write", "path", "b.md", "content", "x".repeat(10))
                                .get("bytes")
                                .asInt())
                .isEqualTo(10);
        assertThat(
                        call(small, "action", "write", "path", "b.md", "content", "x".repeat(11))
                                .asText())
                .isEqualTo("Error: content is larger than 10 bytes (max_bytes)");
        Files.writeString(ws.resolve("c.md"), "1234567\n");
        assertThat(call(small, "action", "append", "path", "c.md", "content", "8").asText())
                .doesNotStartWith("Error");
        assertThat(Files.readString(ws.resolve("c.md"))).isEqualTo("1234567\n8\n");
        assertThat(call(small, "action", "append", "path", "c.md", "content", "").asText())
                .isEqualTo("Error: c.md would grow past 10 bytes (max_bytes)");
        // the line break added in front of an unfinished last line counts too
        Files.writeString(ws.resolve("d.md"), "123456789");
        assertThat(call(small, "action", "append", "path", "d.md", "content", "").asText())
                .isEqualTo("Error: d.md would grow past 10 bytes (max_bytes)");
        Files.writeString(ws.resolve("e.md"), "1234567");
        assertThat(call(small, "action", "append", "path", "e.md", "content", "").asText())
                .doesNotStartWith("Error");
        assertThat(Files.readString(ws.resolve("e.md"))).isEqualTo("1234567\n\n");
        JsonNode cut = call(options("max_bytes", "4"), "action", "read", "path", "a.md");
        assertThat(cut.get("content").asText()).isEqualTo("one\n");
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        assertThat(
                        call(options("max_bytes", "14"), "action", "read", "path", "a.md")
                                .get("truncated")
                                .asBoolean())
                .isFalse();
        assertThat(
                        call(
                                        options("max_bytes", "13"),
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "edits",
                                        List.of(Map.of("old", "one", "new", "1")))
                                .asText())
                .isEqualTo("Error: a.md is larger than 13 bytes (max_bytes)");
        assertThat(
                        call(
                                        options("max_bytes", "14"),
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "edits",
                                        List.of(Map.of("old", "one", "new", "one!")))
                                .asText())
                .isEqualTo("Error: a.md would grow past 14 bytes (max_bytes)");
    }

    @Test
    void appendAddsALineBreakOnlyWhereOneIsMissing() throws Exception {
        Files.writeString(ws.resolve("n.md"), "no break");
        call(options(), "action", "append", "path", "n.md", "content", "next\n");
        assertThat(Files.readString(ws.resolve("n.md"))).isEqualTo("no break\nnext\n");
        call(options(), "action", "append", "path", "new.md", "content", "first");
        assertThat(Files.readString(ws.resolve("new.md"))).isEqualTo("first\n");
    }

    @Test
    void readingLinesAndNumbers() throws Exception {
        assertThat(
                        call(options(), "action", "read", "path", "a.md", "from_line", 3)
                                .get("content")
                                .asText())
                .isEqualTo("three");
        assertThat(
                        call(options(), "action", "read", "path", "a.md", "from_line", 9)
                                .get("content")
                                .asText())
                .isEmpty();
        assertThat(
                        call(options(), "action", "read", "path", "a.md", "lines", 1)
                                .get("content")
                                .asText())
                .isEqualTo("one");
        assertThat(
                        call(
                                        options(),
                                        "action",
                                        "read",
                                        "path",
                                        "a.md",
                                        "from_line",
                                        "2",
                                        "lines",
                                        "5")
                                .get("content")
                                .asText())
                .isEqualTo("two\nthree");
        assertThat(call(options(), "action", "read", "path", "a.md", "from_line", 0).asText())
                .contains("from_line must be 1 or more");
        assertThat(call(options(), "action", "read", "path", "a.md", "lines", "x").asText())
                .contains("lines must be a whole number");
        assertThat(call(options(), "action", "read", "path", "docs").asText())
                .contains("doesn't exist or isn't a file");
        assertThat(call(options(), "action", "read").asText()).contains("path is required");
    }

    @Test
    void listShowsDirectoriesAndSkipsWhatIsIgnoredOrALink() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.createDirectories(ws.resolve("target"));
        Files.createSymbolicLink(ws.resolve("link.md"), ws.resolve("a.md"));
        assertThat(call(options(), "action", "list").get("entries").toString())
                .isEqualTo("[\"a.md\",\"docs/\"]");
        assertThat(call(options(), "action", "list", "path", "a.md").asText())
                .contains("isn't a directory");
        for (int i = 0; i < 4; i++) Files.writeString(ws.resolve("docs/f" + i + ".md"), "x");
        JsonNode capped = call(options("max_matches", "3"), "action", "list", "path", "docs");
        assertThat(capped.get("entries").size()).isEqualTo(3);
        assertThat(capped.get("truncated").asBoolean()).isTrue();
        assertThat(
                        call(options("max_matches", "4"), "action", "list", "path", "docs")
                                .get("truncated")
                                .asBoolean())
                .isFalse();
        assertThat(call(options(), "action", "exists", "path", "link.md").asText())
                .contains("links are not followed");
    }

    /**
     * A program in a sandbox can write into the workspace, so a manifest in the store may be
     * forged: restore must refuse a path that no snapshot of this workspace could hold, and change
     * nothing.
     */
    @ParameterizedTest
    @CsvSource({
        "../escape.txt",
        "/abs.txt",
        "sub/../a.md",
        "./a.md",
        ".git/hooks/pre-commit",
        "sub/.git/config",
        "notes.lock"
    })
    void restoreRefusesAManifestPathNoSnapshotCouldHold(String forged) throws Exception {
        Map<String, String> o = options("protect", "notes.lock");
        call(o, "action", "snapshot", "name", "s");
        Path manifest = dir.resolve("snaps/snapshots/s.json");
        com.fasterxml.jackson.databind.node.ObjectNode m =
                (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(manifest.toFile());
        JsonNode real = m.get("files").get("a.md");
        ((com.fasterxml.jackson.databind.node.ObjectNode) m.get("files")).set(forged, real);
        JSON.writeValue(manifest.toFile(), m);
        Files.writeString(ws.resolve("a.md"), "changed\n");

        assertThat(call(o, "action", "restore", "name", "s").asText())
                .contains("refused")
                .contains("cannot be restored");
        assertThat(dir.resolve("escape.txt")).doesNotExist();
        assertThat(ws.resolve(".git")).doesNotExist();
        assertThat(ws.resolve("notes.lock")).doesNotExist();
        assertThat(ws.resolve("a.md")).hasContent("changed\n");
    }

    @Test
    void aSnapshotNeverHoldsGitEvenWhenIgnoreIsReplaced() throws Exception {
        Files.createDirectories(ws.resolve(".git/hooks"));
        Files.writeString(ws.resolve(".git/hooks/pre-commit"), "echo hi\n");
        Map<String, String> o = options("ignore", "target/**");
        call(o, "action", "snapshot", "name", "s");
        assertThat(Files.readString(dir.resolve("snaps/snapshots/s.json"))).doesNotContain(".git");
        Files.writeString(ws.resolve("a.md"), "changed\n");
        assertThat(call(o, "action", "restore", "name", "s").get("restored").asInt()).isEqualTo(1);
    }

    @Test
    void theDefaultStoreMustBeOutsideTheRootSoASandboxCannotForgeIt() {
        Map<String, String> whole = new HashMap<>(Map.of("root", ".", "allow", "**"));
        assertThatThrownBy(() -> tool(whole))
                .hasMessageContaining("store:")
                .hasMessageContaining("inside the workspace root");
        Map<String, String> sub = new HashMap<>(Map.of("root", "ws", "allow", "**"));
        assertThatCode(() -> tool(sub)).doesNotThrowAnyException();
    }

    @Test
    void findAndListShowOnlyWhatMayBeRead() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.createDirectories(ws.resolve("secrets"));
        Files.createDirectories(ws.resolve("config"));
        Files.writeString(ws.resolve("docs/guide.md"), "g");
        Files.writeString(ws.resolve("docs/.env.local"), "x");
        Files.writeString(ws.resolve(".env"), "x");
        Files.writeString(ws.resolve("secrets/id_rsa"), "x");
        Files.writeString(ws.resolve("config/prod.key"), "x");
        Map<String, String> o = options("allow", "docs/**");

        assertThat(call(o, "action", "find", "pattern", "**").get("paths"))
                .extracting(JsonNode::asText)
                .containsExactly("docs/guide.md");
        assertThat(call(o, "action", "list").get("entries").toString())
                .doesNotContain(".env")
                .doesNotContain("a.md")
                .contains("docs/");
        assertThat(call(o, "action", "list", "path", "docs").get("entries"))
                .extracting(JsonNode::asText)
                .containsExactly("guide.md");
        assertThat(call(o, "action", "list", "path", "config").get("entries")).isEmpty();
    }

    @Test
    void theRunsOwnFilesAndTheStoreAreOutOfReach() throws Exception {
        effects.reserved.add(ws.resolve("journal").toAbsolutePath().normalize());
        Files.createDirectories(ws.resolve("journal"));
        Files.writeString(ws.resolve("journal/run.jsonl"), "x");
        assertThat(call(options(), "action", "read", "path", "journal/run.jsonl").asText())
                .contains("belongs to the run");
        assertThat(call(options(), "action", "find", "pattern", "**").get("paths").toString())
                .doesNotContain("journal");
        assertThat(call(options(), "action", "list").get("entries").toString())
                .doesNotContain("journal");
        // the store is outside the root, so the workspace never lists or reads it
        call(options(), "action", "snapshot", "name", "s");
        assertThat(call(options(), "action", "find", "pattern", "**").get("paths").toString())
                .doesNotContain("snapshots");
        assertThat(call(options(), "action", "read", "path", "a\nb.md").asText())
                .contains("control");
    }

    @Test
    void grepShowsLongLinesCutAndCountsContextAtTheEdges() throws Exception {
        Files.writeString(
                ws.resolve("long.md"),
                "x".repeat(600) + "needle\n" + "y".repeat(2500) + "needle\nlast needle");
        JsonNode r = call(options(), "action", "grep", "text", "needle", "context", 9);
        assertThat(r.get("matches").size()).isEqualTo(2);
        JsonNode first = r.get("matches").get(0);
        assertThat(first.get("text").asText()).hasSize(501).endsWith("…");
        assertThat(first.get("before").size()).isZero();
        assertThat(first.get("after").size()).isEqualTo(2);
        assertThat(r.get("matches").get(1).get("line").asInt()).isEqualTo(3);
        JsonNode exact = call(options("max_matches", "2"), "action", "grep", "text", "needle");
        assertThat(exact.get("truncated").asBoolean()).isFalse();
        Files.writeString(ws.resolve("big.md"), "needle " + "z".repeat(300));
        assertThat(
                        call(options("max_bytes", "100"), "action", "grep", "text", "needle")
                                .get("matches")
                                .toString())
                .doesNotContain("big.md");
        assertThat(call(options(), "action", "grep").asText())
                .contains("text (a literal) or regex");
        assertThat(call(options(), "action", "grep", "text", "a", "regex", "b").asText())
                .contains("one of them");
    }

    @Test
    void diffIsBoundedAndSnapshotsShareStoredFiles() throws Exception {
        call(options(), "action", "snapshot", "name", "one");
        call(options(), "action", "snapshot", "name", "two");
        long objects;
        try (var s = Files.list(dir.resolve("snaps/objects"))) {
            objects = s.count();
        }
        assertThat(objects).as("one copy of a.md for both").isEqualTo(1);
        Files.writeString(ws.resolve("a.md"), "changed");
        call(options(), "action", "snapshot", "name", "one");
        try (var s = Files.list(dir.resolve("snaps/objects"))) {
            assertThat(s.count()).as("the old copy is kept while two needs it").isEqualTo(2);
        }
        call(options(), "action", "snapshot", "name", "two");
        try (var s = Files.list(dir.resolve("snaps/objects"))) {
            assertThat(s.count()).as("then collected").isEqualTo(1);
        }
        for (int i = 0; i < 3; i++) Files.writeString(ws.resolve("n" + i + ".md"), "x");
        JsonNode d = call(options("max_matches", "2"), "action", "diff", "name", "two");
        assertThat(d.get("added").size()).isEqualTo(2);
        assertThat(d.get("truncated").asBoolean()).isTrue();
        Files.delete(ws.resolve("n2.md"));
        JsonNode exact = call(options("max_matches", "2"), "action", "diff", "name", "two");
        assertThat(exact.get("truncated").asBoolean()).isFalse();
        assertThat(exact.get("changed").size()).isZero();
        assertThat(call(options(), "action", "diff").get("snapshot").asText()).isEqualTo("two");
    }

    @Test
    void snapshotCapsAreExact() throws Exception {
        Files.writeString(ws.resolve("b.md"), "12345");
        // a.md is 14 bytes and b.md 5: 19 in all
        assertThat(
                        call(
                                        options("max_files", "2", "max_snapshot_bytes", "19"),
                                        "action",
                                        "snapshot",
                                        "name",
                                        "s")
                                .get("bytes")
                                .asInt())
                .isEqualTo(19);
        assertThat(
                        call(options("max_snapshot_bytes", "18"), "action", "snapshot", "name", "s")
                                .asText())
                .contains("snapshot cap");
        assertThat(
                        call(options("snapshots", "1"), "action", "snapshot", "name", "s")
                                .get("files")
                                .asInt())
                .isEqualTo(2);
    }

    @Test
    void aRestoreUndoesALinkedDirectoryWithoutWritingThroughIt() throws Exception {
        Files.createDirectories(ws.resolve("docs"));
        Files.writeString(ws.resolve("docs/r.md"), "mine");
        call(options(), "action", "snapshot", "name", "s");
        Files.createDirectories(dir.resolve("outside"));
        Files.delete(ws.resolve("docs/r.md"));
        Files.delete(ws.resolve("docs"));
        Files.createSymbolicLink(ws.resolve("docs"), dir.resolve("outside"));
        call(options(), "action", "restore", "name", "s");
        assertThat(Files.isSymbolicLink(ws.resolve("docs"))).isFalse();
        assertThat(ws.resolve("docs/r.md")).hasContent("mine");
        assertThat(dir.resolve("outside/r.md")).doesNotExist();
    }

    @Test
    void anEditMustBeTwoTexts() throws Exception {
        assertThat(call(options(), "action", "patch", "path", "a.md", "edits", "one").asText())
                .contains("a list of {old, new}");
        assertThat(
                        call(
                                        options(),
                                        "action",
                                        "patch",
                                        "path",
                                        "a.md",
                                        "edits",
                                        List.of(
                                                Map.of("old", "one", "new", "1"),
                                                Map.of("old", "x")))
                                .asText())
                .contains("edit 2: give old and new");
        assertThat(
                        call(options(), "action", "patch", "path", "a.md", "edits", List.of("x"))
                                .asText())
                .contains("edit 1: give old and new");
        Files.write(ws.resolve("bin.md"), new byte[] {'o', 'n', 'e', 0});
        assertThat(
                        call(
                                        options(),
                                        "action",
                                        "patch",
                                        "path",
                                        "bin.md",
                                        "edits",
                                        List.of(Map.of("old", "one", "new", "1")))
                                .asText())
                .contains("not text");
    }
}
