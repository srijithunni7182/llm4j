package io.github.llm4j.loom.review.handle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.loom.review.model.Finding;
import io.github.llm4j.loom.review.model.Lens;
import io.github.llm4j.loom.review.model.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The baseline: "everything that exists today is known; tell me only about new problems". It is a
 * file, {@code review.baseline.json}, of the fingerprints of the findings that existed when it was
 * written, with the date and one note. Later reviews list those findings apart and do not count
 * them as open. A baseline never hides a new instance of the same rule, because a new instance has
 * a different fingerprint.
 *
 * <p>Serious findings (high, or in the security, durability and privacy lenses) are left out unless
 * asked for, so a baseline cannot quietly bury the ones that matter most.
 */
public final class Baseline {

    public static final String FILE_NAME = "review.baseline.json";
    public static final int VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Baseline() {}

    /** What writing a baseline did. */
    public record Written(int recorded, int leftOut) {}

    /** True for the findings a baseline leaves out unless told to include them. */
    public static boolean serious(Finding f) {
        return f.severity() == Severity.HIGH
                || f.lens() == Lens.SECURITY
                || f.lens() == Lens.DURABILITY
                || f.lens() == Lens.PRIVACY;
    }

    /** The fingerprints in a baseline file; empty when there is none. */
    public static Set<String> read(Path file) {
        if (!Files.isRegularFile(file)) return Set.of();
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            Set<String> out = new LinkedHashSet<>();
            for (JsonNode e : root.path("entries")) out.add(e.path("fingerprint").asText());
            out.remove("");
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    file + " could not be read as a baseline: " + e.getMessage());
        }
    }

    /** Records {@code findings} (the open ones) in {@code file}, replacing what was there. */
    public static Written write(
            Path file,
            List<Finding> findings,
            boolean includeSerious,
            LocalDate date,
            String note) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("date", date.toString());
        root.put("note", note);
        ArrayNode entries = root.putArray("entries");
        int left = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (Finding f : findings) {
            if (!includeSerious && serious(f)) {
                left++;
                continue;
            }
            if (!seen.add(f.fingerprint())) continue;
            ObjectNode e = entries.addObject();
            e.put("fingerprint", f.fingerprint());
            e.put("rule", f.rule());
            e.put("title", f.title());
        }
        try {
            Files.writeString(
                    file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " could not be written: " + e.getMessage());
        }
        return new Written(seen.size(), left);
    }

    /**
     * Removes the entries whose finding is gone, so the baseline shrinks as problems are fixed.
     *
     * @param still the fingerprints of the findings that exist now (open or baselined)
     * @return how many entries were removed
     */
    public static int prune(Path file, Set<String> still) {
        if (!Files.isRegularFile(file)) return 0;
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file));
            ArrayNode kept = JSON.createArrayNode();
            int removed = 0;
            for (JsonNode e : root.path("entries")) {
                if (still.contains(e.path("fingerprint").asText())) kept.add(e);
                else removed++;
            }
            root.set("entries", kept);
            Files.writeString(
                    file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            return removed;
        } catch (IOException e) {
            throw new IllegalArgumentException(file + " could not be pruned: " + e.getMessage());
        }
    }

    /** For tests and tools: the entries as {@code rule fingerprint}. */
    public static List<String> describe(Path file) {
        List<String> out = new ArrayList<>();
        try {
            for (JsonNode e : JSON.readTree(Files.readString(file)).path("entries"))
                out.add(e.path("rule").asText() + " " + e.path("fingerprint").asText());
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return out;
    }
}
