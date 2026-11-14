package io.github.llm4j.tools.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.ToolRefusal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The git tool's vocabulary: fixed argument lists, hardened, with names and paths checked. */
class GitCommandsTest {

    @Test
    void everyCallTurnsOffWhatARepositoryCouldMakeGitRun() {
        List<String> h = GitCommands.hardened(false);
        assertThat(h).containsSubsequence("-c", "core.hooksPath=/dev/null");
        assertThat(h).contains("core.fsmonitor=false", "core.pager=cat", "core.editor=false", "diff.external=",
                "protocol.allow=never", "protocol.https.allow=always", "credential.helper=", "--no-pager");
        assertThat(h).noneMatch(s -> s.contains("GIT_TOKEN"));
        assertThat(GitCommands.hardened(true)).anyMatch(s -> s.contains("$GIT_TOKEN")).noneMatch(s -> s.contains("sk-"));
    }

    @Test
    void theCommandsAreFixedAndNeverForce() {
        assertThat(GitCommands.status()).containsExactly("status", "--porcelain=v1", "--branch");
        assertThat(GitCommands.diff(true, "src/A.java")).containsSubsequence("diff", "--no-ext-diff", "--no-textconv", "--cached", "--", "src/A.java");
        assertThat(GitCommands.diff(false, null)).doesNotContain("--cached", "--");
        assertThat(GitCommands.log(5)).endsWith("-n", "5");
        assertThat(GitCommands.branches()).startsWith("branch", "--list");
        assertThat(GitCommands.newBranch("agent/fix-1")).containsExactly("switch", "-c", "agent/fix-1");
        assertThat(GitCommands.add(List.of())).containsExactly("add", "--", ".");
        assertThat(GitCommands.add(List.of("a.txt", "b/c.txt"))).containsExactly("add", "--", "a.txt", "b/c.txt");
        assertThat(GitCommands.commit("-rf message", "A", "a@b.c")).containsSubsequence("commit", "--no-verify", "--no-gpg-sign", "-m", "-rf message");
        assertThat(GitCommands.push("origin", "agent/x")).containsExactly("push", "--no-verify", "origin", "HEAD:refs/heads/agent/x");
        assertThat(GitCommands.push("origin", "agent/x")).noneMatch(s -> s.startsWith("--force") || s.startsWith("+"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-f", "--upload-pack=x", "a..b", "a//b", "a/", "x.lock", "a.", "", "a b", "..", "+main", "a:b", "a~1"})
    void aNameThatIsNotABranchIsRefused(String name) {
        assertThatThrownBy(() -> GitCommands.ref(name)).isInstanceOf(ToolRefusal.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-p", "/etc/passwd", "../x", "a/../../x", ":(top)", "a\\b", " "})
    void aPathOutsideTheWorkspaceOrThatIsAnOptionIsRefused(String path) {
        assertThatThrownBy(() -> GitCommands.path(path)).isInstanceOf(ToolRefusal.class);
    }

    @Test
    void boundsHold() {
        assertThatThrownBy(() -> GitCommands.log(0)).isInstanceOf(ToolRefusal.class);
        assertThatThrownBy(() -> GitCommands.log(51)).isInstanceOf(ToolRefusal.class);
        assertThat(GitCommands.log(50)).contains("50");
        assertThatThrownBy(() -> GitCommands.commit(" ", "A", "a@b.c")).hasMessageContaining("required");
        assertThatThrownBy(() -> GitCommands.commit(null, "A", "a@b.c")).hasMessageContaining("required");
        assertThatThrownBy(() -> GitCommands.commit("x".repeat(2001), "A", "a@b.c")).hasMessageContaining("longer");
        assertThatThrownBy(() -> GitCommands.author("-x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitCommands.email("nobody")).isInstanceOf(IllegalArgumentException.class);
        assertThat(GitCommands.author("llm4j agent")).isEqualTo("llm4j agent");
    }
}
