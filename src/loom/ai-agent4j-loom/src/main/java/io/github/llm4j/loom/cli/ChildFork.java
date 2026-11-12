package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.execution.ChildRuns;
import io.github.llm4j.loom.runtime.FileRunJournal;
import io.github.llm4j.loom.runtime.RunJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code weave fork} follows the links from a run to its children (decision D3): copying a run copies
 * the runs it started, under the ids the copy will look for, so the copy resumes its own children and
 * never shares one with the original.
 */
final class ChildFork {

    private ChildFork() {}

    /**
     * Copies the children recorded in {@code copiedJournal} (already the copy's journal) from the
     * folders of {@code fromRunDir} to those of {@code toRunDir}, and rewrites each start entry to
     * the child's new id. Grandchildren follow. Returns how many child runs were copied.
     */
    static int copy(Path fromRunDir, Path toRunDir, RunJournal copiedJournal) {
        FolderChildRuns from = new FolderChildRuns(fromRunDir);
        FolderChildRuns to = new FolderChildRuns(toRunDir);
        return copyLevel(from, to, toRunDir.toAbsolutePath().normalize().toString(), copiedJournal);
    }

    /**
     * The children of a run drawn as a tree under it, each with its own timeline indented (D16): a
     * child is a run of its own, so it is read the way any run is.
     */
    static String tree(Path runDir, RunJournal journal) {
        return tree(new FolderChildRuns(runDir), journal, "  ");
    }

    private static String tree(FolderChildRuns runs, RunJournal journal, String indent) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, RunJournal.Entry> e : new TreeMap<>(journal.all()).entrySet()) {
            if (!"child".equals(e.getValue().kind()) || !(e.getValue().value() instanceof Map<?, ?> m)) continue;
            String id = String.valueOf(m.get("run"));
            out.append(indent).append("child ").append(m.get("workflow")).append(" (step ").append(e.getKey()).append(", run ").append(id).append(")\n");
            if (!Files.isDirectory(runs.folderOf(id))) {
                out.append(indent).append("  (it never got a run of its own on disk)\n");
                continue;
            }
            RunJournal childJournal = runs.openJournal(id);
            for (String line : io.github.llm4j.loom.travel.RunTravel.timeline(childJournal).text().split("\n"))
                if (!line.isBlank()) out.append(indent).append("  ").append(line).append('\n');
            out.append(tree(runs, childJournal, indent + "  "));
        }
        return out.toString();
    }

    private static int copyLevel(FolderChildRuns from, FolderChildRuns to, String newParentId, RunJournal journal) {
        int copied = 0;
        for (Map.Entry<String, RunJournal.Entry> e : new TreeMap<>(journal.all()).entrySet()) {
            if (!"child".equals(e.getValue().kind()) || !(e.getValue().value() instanceof Map<?, ?> m)) continue;
            String oldId = String.valueOf(m.get("run"));
            String workflow = String.valueOf(m.get("workflow"));
            String newId = ChildRuns.idOf(newParentId, e.getKey());
            Path source = from.folderOf(oldId);
            if (!Files.isDirectory(source)) continue; // that child never got as far as a folder
            RunJournal fromChild = from.openJournal(oldId);
            RunJournal toChild = to.openJournal(newId);
            fromChild.all().forEach(toChild::put);
            to.record(newId, newParentId, workflow);
            Map<String, Object> entry = new LinkedHashMap<>();
            m.forEach((k, v) -> entry.put(String.valueOf(k), v));
            entry.put("run", newId);
            journal.put(e.getKey(), new RunJournal.Entry("child", entry));
            copied += 1 + copyLevel(from, to, newId, toChild);
        }
        return copied;
    }
}
