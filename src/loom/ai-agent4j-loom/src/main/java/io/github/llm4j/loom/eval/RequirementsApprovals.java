package io.github.llm4j.loom.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.policy.PolicyCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * A person's sign-off on the requirements as they stand (docs/specs/28-evaluation-first, R5). It
 * records who, when and why, against an exact identity: the dataset's hash plus the version and
 * hash of every policy. A later change to any case or rule makes the approval stale. It is a review
 * aid, in the same spirit as a baseline's approval, not a security control.
 */
public final class RequirementsApprovals {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String FILE = "requirements-approvals.yaml";

    private RequirementsApprovals() {}

    /** One recorded approval. */
    public record Approval(String approver, String why, String date, String identity) {}

    /** Records an approval of exactly what the script's requirements are now. */
    public static Approval approve(
            LoomScript script, Path scriptFile, String approver, String why, Clock clock) {
        if (approver == null || approver.isBlank())
            throw new IllegalArgumentException(
                    "an approval needs an approver: --approver <name>");
        if (why == null || why.isBlank())
            throw new IllegalArgumentException("an approval needs a reason: --why \"<text>\"");
        Approval approval =
                new Approval(
                        approver.strip(),
                        why.strip(),
                        LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(),
                        identity(script, scriptFile));
        Path file = file(scriptFile);
        try {
            ArrayNode all = YAML.createArrayNode();
            readAll(file).forEach(all::add);
            all.add(YAML.valueToTree(approval));
            Files.createDirectories(file.getParent());
            Files.writeString(file, YAML.writeValueAsString(all));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not write " + file + ": " + e.getMessage() + ". Check the folder is writable.", e);
        }
        return approval;
    }

    /** The latest approval, or empty when there is none. */
    public static Optional<Approval> latest(Path scriptFile) {
        JsonNode last = null;
        for (JsonNode n : readAll(file(scriptFile))) last = n;
        if (last == null) return Optional.empty();
        return Optional.of(
                new Approval(
                        last.path("approver").asText(),
                        last.path("why").asText(),
                        last.path("date").asText(),
                        last.path("identity").asText()));
    }

    /** What the requirements are right now: the dataset's hash and every policy's version and hash. */
    public static String identity(LoomScript script, Path scriptFile) {
        Path dir = DatasetFolder.locate(scriptFile, null);
        StringBuilder id = new StringBuilder("dataset:");
        id.append(java.nio.file.Files.isDirectory(dir) ? DatasetIdentity.of(dir).hash() : "none");
        PolicyCatalog catalog = PolicyCatalog.beside(scriptFile.toAbsolutePath().getParent());
        for (PolicyDef p : script.getPolicies())
            id.append(';')
                    .append(p.getName())
                    .append(':')
                    .append(
                            catalog.resolve(p)
                                    .map(r -> r.version() + "/" + r.hash())
                                    .orElse("unreadable"));
        return id.toString();
    }

    /** The folder's approvals file sits beside {@code eval/golden}, in {@code eval/}. */
    static Path file(Path scriptFile) {
        Path golden = DatasetFolder.locate(scriptFile, null);
        return golden.getParent().resolve(FILE);
    }

    private static Iterable<JsonNode> readAll(Path file) {
        try {
            if (!Files.isRegularFile(file)) return java.util.List.of();
            JsonNode root = YAML.readTree(Files.readString(file));
            return root != null && root.isArray() ? root : java.util.List.of();
        } catch (IOException e) {
            throw new IllegalStateException(
                    file + " could not be read: " + e.getMessage() + ". Fix or remove it.", e);
        }
    }
}
