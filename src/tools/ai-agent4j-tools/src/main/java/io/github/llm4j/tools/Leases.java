package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The leases of served programs (D7): one small file per program a {@code sandbox} keeps running,
 * in its workspace's store. A lease names the program's handle, the sandbox tool that owns it, the
 * loopback port it is published on and its deadline. The sandbox writes them; a tool that reaches
 * a served program ({@code http} with {@code reaches:}) reads them, and so does {@code workspace
 * restore}, which waits while one is live.
 */
final class Leases {

    /** One served program. */
    record Lease(String handle, String owner, int port, long deadline) {
        Lease {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(owner, "owner");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HANDLE = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}";

    private final Path dir;
    private final Clock clock;

    Leases(Path store, Clock clock) {
        this.dir = store.resolve("leases");
        this.clock = clock;
    }

    /** Whether a handle has the shape this tool gives out; anything else is refused before use. */
    static String checkHandle(String handle) {
        if (handle == null || !handle.matches(HANDLE)) throw new ToolRefusal("handle is not one this tool gave out");
        return handle;
    }

    void write(Lease lease) throws IOException {
        Files.createDirectories(dir);
        JSON.writeValue(file(lease.handle()).toFile(), lease);
    }

    void delete(String handle) throws IOException {
        Files.deleteIfExists(file(handle));
    }

    /** The lease of {@code handle}, whoever owns it. */
    Optional<Lease> get(String handle) throws IOException {
        Path f = file(checkHandle(handle));
        return Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) ? Optional.of(read(f)) : Optional.empty();
    }

    /** Every lease on disk, in handle order. */
    List<Lease> all() throws IOException {
        List<Lease> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) out.add(read(f));
        }
        return out;
    }

    /** The leases {@code owner} holds. */
    List<Lease> of(String owner) throws IOException {
        return all().stream().filter(l -> l.owner().equals(owner)).toList();
    }

    /** Whether a lease is still within its deadline. */
    boolean live(Lease lease) {
        return lease.deadline() > clock.millis();
    }

    /**
     * The one live lease of {@code owner} a call reaches: the named handle, or the only one when
     * none is named. Refuses with what to do when there is none, or more than one.
     */
    Lease reach(String owner, String handle) throws IOException {
        List<Lease> live = of(owner).stream().filter(this::live).toList();
        if (handle != null) {
            checkHandle(handle);
            return live.stream()
                    .filter(l -> l.handle().equals(handle))
                    .findFirst()
                    .orElseThrow(() -> new ToolRefusal(owner + " serves no program " + handle + " now (it ended, or was stopped)"));
        }
        if (live.isEmpty()) throw new ToolRefusal(owner + " serves nothing now: start it with action serve first");
        if (live.size() > 1)
            throw new ToolRefusal(owner + " serves " + live.size() + " programs; name one with handle (" + live.stream().map(Lease::handle).toList() + ")");
        return live.get(0);
    }

    private Path file(String handle) {
        return dir.resolve(handle + ".json");
    }

    private static Lease read(Path f) throws IOException {
        return JSON.readValue(f.toFile(), Lease.class);
    }
}
