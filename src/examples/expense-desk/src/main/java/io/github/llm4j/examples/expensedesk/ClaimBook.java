package io.github.llm4j.examples.expensedesk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The desk's claims, kept in a file so they are still there after a restart, beside the run
 * journals the workflow host keeps. Thread-safe.
 */
public final class ClaimBook {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final Map<String, Claim> claims = new LinkedHashMap<>();

    /** Opens the book in {@code file}, reading the claims an earlier run of the desk left. */
    public ClaimBook(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        if (Files.isRegularFile(file)) {
            try {
                for (Claim claim : JSON.readValue(file.toFile(), new TypeReference<List<Claim>>() {})) {
                    claims.put(claim.id(), claim);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("the claims in " + file + " could not be read; move the file away to start empty", e);
            }
        }
    }

    public synchronized void add(Claim claim) {
        claims.put(claim.id(), claim);
        save();
    }

    /** Changes one claim; an id the book does not know is ignored. */
    public synchronized void update(String id, UnaryOperator<Claim> change) {
        Claim before = claims.get(id);
        if (before == null) return;
        claims.put(id, change.apply(before));
        save();
    }

    public synchronized Optional<Claim> find(String id) {
        return Optional.ofNullable(claims.get(id));
    }

    /** Every claim, newest first. */
    public synchronized List<Claim> all() {
        List<Claim> newestFirst = new ArrayList<>(claims.values());
        java.util.Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private void save() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path draft = Files.createTempFile(parent, "claims", ".json");
            JSON.writeValue(draft.toFile(), new ArrayList<>(claims.values()));
            Files.move(draft, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("the claims could not be saved to " + file, e);
        }
    }
}
