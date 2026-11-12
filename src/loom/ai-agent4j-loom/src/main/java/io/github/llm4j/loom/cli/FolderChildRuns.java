package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Where {@code weave run --journal DIR} keeps the runs of started workflows (decision D3): each
 * child is a sibling folder of the parent's run folder, {@code DIR.child-<digest>}, holding its own
 * {@code journal.json} and a {@code child.json} that names its parent. The same child id always
 * gives the same folder, so a resumed parent finds the child it started.
 */
final class FolderChildRuns implements ChildRuns {

    private static final int DIGEST_CHARS = 16;

    private final Path parentRunDir;

    FolderChildRuns(Path parentRunDir) {
        this.parentRunDir = parentRunDir.toAbsolutePath().normalize();
    }

    /** The folder of the child {@code childRunId}. */
    Path folderOf(String childRunId) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(childRunId.getBytes(StandardCharsets.UTF_8));
            String name = parentRunDir.getFileName() + ".child-" + HexFormat.of().formatHex(digest).substring(0, DIGEST_CHARS);
            return parentRunDir.resolveSibling(name);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    @Override
    public RunJournal openJournal(String childRunId) {
        return new FileRunJournal(folderOf(childRunId).resolve("journal.json"));
    }

    @Override
    public void record(String childRunId, String parentRunId, String workflow) {
        Path file = folderOf(childRunId).resolve("child.json");
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    "{\"run\": " + quote(childRunId) + ", \"parent\": " + quote(parentRunId) + ", \"parentFolder\": "
                            + quote(parentRunDir.toString()) + ", \"workflow\": " + quote(workflow) + "}\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot record the child " + childRunId + " in " + file, e);
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
