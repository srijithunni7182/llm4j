package io.github.llm4j.tools.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.llm4j.agent.tool.EffectContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * With the real {@code git} (process isolation, the weakest level): a hook the repository holds
 * never runs, and a commit lands. Needs git on the PATH.
 */
@Tag("integration")
class RealGitTest {

    @TempDir Path dir;

    static int git(Path repo, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
        return p.exitValue();
    }

    @Test
    void aHookInTheRepositoryNeverRunsAndTheCommitLands() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("work"));
        try {
            assumeTrue(git(repo, "init", "-q") == 0, "git is not installed here");
        } catch (java.io.IOException none) {
            assumeTrue(false, "git is not installed here");
        }
        // post-commit, which --no-verify does not skip: only core.hooksPath keeps it from running
        Path hook = repo.resolve(".git/hooks/post-commit");
        Files.writeString(hook, "#!/bin/sh\ntouch \"" + dir.resolve("hook-ran") + "\"\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        var tool = new GitKind().create("Repo", Map.of(
                "workspace", "Code", "workspace.root", "work", "isolation", "process", "network", "open"), dir, EffectContext.noop());
        String out = tool.execute(Map.of("action", "commit", "message", "First"));
        assertThat(out).doesNotStartWith("Error:");
        assertThat(dir.resolve("hook-ran")).as("the hook did not run").doesNotExist();
        assertThat(tool.execute(Map.of("action", "log"))).contains("First").contains("llm4j agent");
        assertThat(tool.execute(Map.of("action", "status"))).contains("\"exit\":0");
    }

    /**
     * A filter driver planted where git reads configuration besides .git/config: real git runs it
     * (shown first, outside the tool), and the tool refuses the repository, so it never does.
     */
    @Test
    void aFilterPlantedInACommonDirectoryOrAWorktreeConfigNeverRuns() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("work"));
        try {
            assumeTrue(git(repo, "init", "-q") == 0, "git is not installed here");
        } catch (java.io.IOException none) {
            assumeTrue(false, "git is not installed here");
        }
        Path ran = dir.resolve("filter-ran");
        String filter = "[filter \"x\"]\n\tclean = touch " + ran + "; cat\n";
        Files.writeString(repo.resolve(".gitattributes"), "* filter=x\n");
        Files.writeString(repo.resolve("a.txt"), "hello\n");
        // config.worktree, read when extensions.worktreeConfig is on
        assumeTrue(git(repo, "config", "extensions.worktreeConfig", "true") == 0, "this git has no worktreeConfig");
        Files.writeString(repo.resolve(".git/config.worktree"), filter);
        git(repo, "add", "--", "a.txt");
        assertThat(ran).as("real git runs a filter from config.worktree").exists();
        Files.delete(ran);
        git(repo, "reset", "-q");
        var tool = new GitKind().create("Repo", Map.of(
                "workspace", "Code", "workspace.root", "work", "isolation", "process", "network", "open"), dir, EffectContext.noop());
        assertThat(tool.execute(Map.of("action", "commit", "message", "First"))).contains("refused").contains("config.worktree");
        assertThat(ran).as("the tool refused before git ran").doesNotExist();
        // a common directory: the repository's configuration lives in another folder
        Files.delete(repo.resolve(".git/config.worktree"));
        Path common = Files.createDirectories(dir.resolve("common"));
        Files.writeString(repo.resolve(".git/commondir"), common.toString() + "\n");
        assertThat(tool.execute(Map.of("action", "commit", "message", "First"))).contains("refused").contains("commondir");
        assertThat(ran).doesNotExist();
    }
}
