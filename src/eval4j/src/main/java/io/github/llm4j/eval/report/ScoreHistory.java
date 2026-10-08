package io.github.llm4j.eval.report;

import java.util.List;

/** Persistent per-run score history, used for trend charts. See {@link FileSystemScoreHistory}. */
public interface ScoreHistory {

    void append(HistoryEntry entry);

    /** All retained entries, oldest first. */
    List<HistoryEntry> load();
}
