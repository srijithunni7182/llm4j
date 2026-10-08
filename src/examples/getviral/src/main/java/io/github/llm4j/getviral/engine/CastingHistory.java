package io.github.llm4j.getviral.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every casting the Showrunner has written for a creator: the lens, the creative direction, the
 * signature ideas it leaned on and the prompts it wrote. The next casting is briefed with it and
 * checked against it, so the orchestrator can't keep reaching for the same angle.
 */
public interface CastingHistory {

    /** How many recent castings are kept and shown to the Showrunner. */
    int KEEP = 12;

    /**
     * One run's creative choices: the Showrunner's lens, direction, signature ideas and visual style; the
     * ArtDirector's final style; the YouTube titles and thumbnail; the hook the creator picked.
     */
    record PastCasting(String runId, String createdAt, String idea, String niche, String runTitle, String lens,
                       String creativeDirection, List<String> signatureIdeas, String visualStyle, String artStyle,
                       List<String> youtubeTitles, String thumbnailConcept, String hook, Map<String, String> prompts) {

        /** One line for the Showrunner's briefing. */
        public String line() {
            StringBuilder b = new StringBuilder("- ").append(createdAt == null ? "" : createdAt.substring(0, Math.min(10, createdAt.length())))
                    .append(" \"").append(idea).append("\" — lens: ").append(lens)
                    .append("; visual style: ").append(artStyle == null || artStyle.isBlank() ? visualStyle : artStyle);
            if (signatureIdeas != null && !signatureIdeas.isEmpty()) b.append("; signature ideas: ").append(String.join(", ", signatureIdeas));
            if (youtubeTitles != null && !youtubeTitles.isEmpty()) b.append("; YouTube titles: \"").append(String.join("\" / \"", youtubeTitles)).append('"');
            if (thumbnailConcept != null && !thumbnailConcept.isBlank()) b.append("; thumbnail: ").append(thumbnailConcept);
            if (hook != null && !hook.isBlank()) b.append("; hook: \"").append(hook).append('"');
            return b.toString();
        }
    }

    /** The briefing text for a list of past castings (or a note that there are none). */
    static String brief(List<PastCasting> history) {
        if (history.isEmpty()) return "(none yet — this is the creator's first pack, so set a distinctive baseline)";
        return String.join("\n", history.stream().map(PastCasting::line).toList());
    }

    /** Newest first. */
    List<PastCasting> recent(String creatorKey, int limit);

    void record(String creatorKey, PastCasting casting);

    /** Local mode: one JSON file per creator under {@code <data>/casting/}. */
    static CastingHistory files(Path dataDir) {
        return new FileCastingHistory(dataDir.resolve("casting"));
    }

    final class FileCastingHistory implements CastingHistory {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path dir;

        FileCastingHistory(Path dir) {
            this.dir = dir;
        }

        private Path file(String creatorKey) {
            return dir.resolve(creatorKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        }

        @Override
        public synchronized List<PastCasting> recent(String creatorKey, int limit) {
            Path file = file(creatorKey);
            if (!Files.exists(file)) return List.of();
            try {
                List<PastCasting> all = JSON.readValue(file.toFile(), new TypeReference<List<PastCasting>>() { });
                return all.subList(0, Math.min(limit, all.size()));
            } catch (IOException e) {
                return List.of();
            }
        }

        @Override
        public synchronized void record(String creatorKey, PastCasting casting) {
            List<PastCasting> all = new ArrayList<>(recent(creatorKey, KEEP));
            all.add(0, casting);
            try {
                Files.createDirectories(dir);
                JSON.writerWithDefaultPrettyPrinter().writeValue(file(creatorKey).toFile(),
                        all.subList(0, Math.min(KEEP, all.size())));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot save casting history", e);
            }
        }
    }
}
