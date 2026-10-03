package io.github.llm4j.eval.export;

import java.nio.file.Path;

/**
 * Notified when a run bundle is complete. Register through {@code
 * META-INF/services/io.github.llm4j.eval.export.RunExportListener}; used by {@code eval4j-report}
 * to render a dashboard at the end of a test JVM.
 */
public interface RunExportListener {

    /**
     * @param runDir the bundle directory ({@code <root>/runs/<runId>})
     */
    void runFinished(Path root, Path runDir);
}
