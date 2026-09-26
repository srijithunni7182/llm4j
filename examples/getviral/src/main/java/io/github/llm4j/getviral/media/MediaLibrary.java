package io.github.llm4j.getviral.media;

import io.github.llm4j.getviral.studio.StudioEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Everything a run generated, on disk under {@code <data>/media/<runId>/} and announced to the studio. */
public class MediaLibrary {

    private final String runId;
    private final Path dir;
    private final StudioEvents events;
    private final List<MediaAsset> assets = new CopyOnWriteArrayList<>();
    private final Sink sink;

    /** Mirrors finished files elsewhere (e.g. cloud storage) so any server instance can serve them. */
    @FunctionalInterface
    public interface Sink {
        void stored(String runId, Path file);

        Sink NONE = (runId, file) -> { };
    }

    public MediaLibrary(Path dataDir, String runId, StudioEvents events) {
        this(dataDir, runId, events, Sink.NONE);
    }

    public MediaLibrary(Path dataDir, String runId, StudioEvents events, Sink sink) {
        this.sink = sink != null ? sink : Sink.NONE;
        this.runId = runId;
        this.dir = dataDir.resolve("media").resolve(runId);
        this.events = events != null ? events : StudioEvents.NONE;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create media directory " + dir, e);
        }
    }

    /** A fresh file in this run's folder, e.g. {@code file("thumbnail", "png")}. */
    public Path file(String purpose, String extension) {
        String base = purpose.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isEmpty()) base = "asset";
        Path candidate = dir.resolve(base + "." + extension);
        for (int i = 2; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + "-" + i + "." + extension);
        }
        return candidate;
    }

    public String urlFor(Path file) {
        return "/media/" + runId + "/" + file.getFileName();
    }

    public MediaAsset add(String kind, String purpose, Path file, String provider, boolean ai, String prompt,
                          int width, int height, double seconds) {
        return add(kind, purpose, file, provider, ai, prompt, width, height, seconds, null);
    }

    public MediaAsset add(String kind, String purpose, Path file, String provider, boolean ai, String prompt,
                          int width, int height, double seconds, Path plate) {
        MediaAsset asset = new MediaAsset(kind, purpose, urlFor(file), file, provider, ai, prompt, width, height, seconds, plate);
        sink.stored(runId, file);
        assets.add(asset);
        events.emit("media", asset.toMap());
        return asset;
    }

    public List<MediaAsset> assets() {
        return List.copyOf(assets);
    }

    public List<MediaAsset> images() {
        return assets.stream().filter(a -> a.kind().equals("image")).toList();
    }

    public Optional<MediaAsset> find(String purposePrefix) {
        return assets.stream().filter(a -> a.purpose().startsWith(purposePrefix)).reduce((a, b) -> b);
    }

    public Path dir() {
        return dir;
    }
}
