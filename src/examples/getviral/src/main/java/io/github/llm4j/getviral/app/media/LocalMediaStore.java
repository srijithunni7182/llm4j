package io.github.llm4j.getviral.app.media;

import java.nio.file.Files;
import java.nio.file.Path;

/** Development store: files already live on the local disk. */
public class LocalMediaStore implements MediaStore {

    @Override
    public void put(String runId, Path file) { }

    @Override
    public boolean fetch(String runId, String fileName, Path local) {
        return Files.isRegularFile(local);
    }

    @Override
    public void deleteRun(String runId) { }
}
