package io.github.llm4j.getviral.app.media;

import java.nio.file.Path;

/**
 * Durable home for generated media. The engine writes files locally while it works; the store makes
 * them available to every instance (Cloud Storage in production, the local disk in development).
 */
public interface MediaStore {

    /** Called for each finished file under {@code <data>/media/<runId>/}. */
    void put(String runId, Path file);

    /** Ensures {@code local} exists (downloading if needed); false if the object is unknown. */
    boolean fetch(String runId, String fileName, Path local);

    /** Removes every object of a run (account deletion). */
    void deleteRun(String runId);
}
