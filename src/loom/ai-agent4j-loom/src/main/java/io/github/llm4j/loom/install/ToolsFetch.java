package io.github.llm4j.loom.install;

import io.github.llm4j.BuildIdentity;
import io.github.llm4j.loom.runtime.HumanInterface;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code weave tools fetch}: the Fabric server and the inspect add-on a deployment needs, from
 * Maven Central, for the version this weave was released as. Nothing is downloaded without being
 * named and asked for, a file is kept only if its published checksum matches, a development build
 * is refused, and files are written only under the tools folder. What is downloaded is never run
 * or loaded here.
 */
public final class ToolsFetch {
    /** What can be fetched. */
    public enum Which {
        FABRIC,
        INSPECT,
        ALL
    }

    /** How bytes are fetched, so tests need no network. */
    public interface Fetcher {
        /** The size of the file in bytes, when the server says. */
        OptionalLong size(URI uri) throws IOException;

        /** The file, or empty when there is none; fails when it is larger than {@code maxBytes}. */
        Optional<byte[]> get(URI uri, long maxBytes) throws IOException;
    }

    private static final String HOST = "repo1.maven.org";
    private static final String BASE = "https://" + HOST + "/maven2/io/github/srijithunni7182/";
    /** A bound on one download: the biggest jar here is a few tens of megabytes. */
    private static final long MAX_BYTES = 250L * 1024 * 1024;

    private record Item(String artifact, String classifier, String envVariable) {
        String file(String version) {
            return artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        }

        URI uri(String version) {
            return URI.create(BASE + artifact + "/" + version + "/" + file(version));
        }
    }

    private static final Item FABRIC = new Item("loom-fabric", "all", "FABRIC_JAR");
    private static final Item INSPECT = new Item("loom-inspect", null, "INSPECT_JAR");
    private static final Item INSPECT_CORE = new Item("loom-inspect-core", null, "INSPECT_CORE_JAR");

    private ToolsFetch() {}

    /** True for an https address on Maven Central, the only place this ever asks. */
    public static boolean allowed(URI uri) {
        return "https".equals(uri.getScheme())
                && HOST.equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().startsWith("/maven2/");
    }

    /**
     * @return 0 done, 1 a download failed or did not match its checksum, 2 refused or declined
     */
    public static int fetch(
            Which which,
            BuildIdentity running,
            Path toolsDir,
            Fetcher fetcher,
            HumanInterface human,
            boolean yes,
            PrintStream out,
            PrintStream err) {
        if (running.fromClasses() || running.isDevelopment() || running.version().contains("SNAPSHOT")) {
            err.println(
                    "This is a development build ("
                            + running.describe()
                            + "), and a development build has nothing on Maven Central to fetch."
                            + " Build the module yourself (mvn -DskipTests -pl src/loom/loom-fabric -am package),"
                            + " or install a release.");
            return 2;
        }
        String version = running.version();
        List<Item> items = new ArrayList<>();
        if (which == Which.FABRIC || which == Which.ALL) items.add(FABRIC);
        if (which == Which.INSPECT || which == Which.ALL) {
            items.add(INSPECT);
            items.add(INSPECT_CORE);
        }
        long total = 0;
        out.println("Fetching version " + version + " from " + HOST + " (nothing else is contacted):");
        for (Item item : items) {
            URI uri = item.uri(version);
            OptionalLong size = OptionalLong.empty();
            try {
                size = fetcher.size(uri);
            } catch (IOException e) {
                // the size is only for the question; the download reports a real failure
            }
            total += size.orElse(0);
            out.println(
                    "  "
                            + uri
                            + (size.isPresent() ? "  (" + megabytes(size.getAsLong()) + ")" : ""));
        }
        Path target = toolsDir.resolve(version);
        out.println("into " + target + ", each file checked against its published checksum.");
        if (!yes && !agrees(human.promptHuman("Download " + items.size() + " file(s)? (yes/no)"))) {
            err.println("Nothing was downloaded.");
            return 2;
        }
        try {
            Files.createDirectories(target);
            List<String> placed = new ArrayList<>();
            for (Item item : items) {
                Path placedAt = download(item, version, target, fetcher, out, err);
                if (placedAt == null) return 1;
                placed.add(item.envVariable() + "=" + placedAt);
            }
            out.println("Done. To use them:");
            placed.forEach(p -> out.println("  export " + p));
            return 0;
        } catch (IOException e) {
            err.println("Could not download: " + e.getMessage());
            return 1;
        }
    }

    private static Path download(
            Item item, String version, Path target, Fetcher fetcher, PrintStream out, PrintStream err)
            throws IOException {
        URI uri = item.uri(version);
        if (!allowed(uri)) throw new IOException("refusing to contact " + uri);
        byte[] bytes =
                fetcher.get(uri, MAX_BYTES)
                        .orElseThrow(() -> new IOException(uri + " was not found on Maven Central"));
        String algorithm = "SHA-256";
        Optional<byte[]> published = fetcher.get(URI.create(uri + ".sha256"), 1024);
        if (published.isEmpty()) {
            algorithm = "SHA-1";
            published = fetcher.get(URI.create(uri + ".sha1"), 1024);
            if (published.isPresent())
                out.println("  Central publishes only a SHA-1 for " + item.file(version) + "; checking that.");
        }
        if (published.isEmpty()) {
            err.println(
                    "No checksum is published for "
                            + item.file(version)
                            + ", so it is not installed.");
            return null;
        }
        String expected =
                new String(published.get(), StandardCharsets.UTF_8).strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        String actual = hex(algorithm, bytes);
        if (!expected.equals(actual)) {
            err.println(
                    "The "
                            + algorithm
                            + " of "
                            + item.file(version)
                            + " does not match the one Maven Central publishes, so it is not installed.");
            return null;
        }
        Path destination = target.resolve(item.file(version)).normalize();
        if (!destination.startsWith(target.normalize()))
            throw new IOException("refusing to write outside " + target);
        Path temp = Files.createTempFile(target, ".fetch-", ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        out.println("  " + item.file(version) + ": " + algorithm + " matches (" + megabytes(bytes.length) + ")");
        return destination;
    }

    private static boolean agrees(String answer) {
        String a = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        return a.equals("y") || a.equals("yes");
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
