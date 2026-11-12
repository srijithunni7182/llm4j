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

    /** The file in a child's folder that says whose child it is. */
    static final String CHILD_FILE = "child.json";

    /** True when {@code runDir} is the folder of a started workflow, not a run of its own. */
    static boolean isChildFolder(Path runDir) {
        return Files.exists(runDir.resolve(CHILD_FILE));
    }

    @Override
    public void record(String childRunId, String parentRunId, String workflow) {
        Path file = folderOf(childRunId).resolve(CHILD_FILE);
        if (Files.exists(file)) return;
        writeRunSpec(childRunId, workflow);
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

    /**
     * Gives the child's folder a {@code run.json}, so {@code weave why}, {@code timeline} and the other
     * commands that explain a run open it as they open any run (F6): the parent's script and settings,
     * the child's workflow, and no inputs (a {@code start}'s arguments are not kept in the record). A
     * parent with no {@code run.json} (a run made some other way) leaves the folder as it was. The
     * folder is still not a run to resume: {@link Runs#execute} refuses it, naming the parent.
     */
    private void writeRunSpec(String childRunId, String workflow) {
        Path folder = folderOf(childRunId);
        if (Files.exists(folder.resolve(RunSpec.FILE)) || !Files.exists(parentRunDir.resolve(RunSpec.FILE))) return;
        RunSpec parent = RunSpec.read(parentRunDir);
        new RunSpec(
                        parent.script(), parent.loot(), workflow, java.util.Map.of(), parent.maxTokens(), parent.maxCalls(),
                        parent.maxCost(), parent.prices(), parent.store(), parent.lenient(), parent.trace())
                .write(folder);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
