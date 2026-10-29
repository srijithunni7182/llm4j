package io.github.llm4j.loom.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a dataset is, for deciding whether two runs may be compared: the {@code version:} its
 * {@code dataset.yaml} declares (null when it declares none) and a hash of every dataset file. The
 * hash means a changed case is a changed dataset even when nobody bumped the version.
 *
 * @param declared the declared version, or null
 * @param hash the first twelve hex characters of a SHA-256 over the dataset files
 */
public record DatasetIdentity(String declared, String hash) {

    /** Reads the identity of the dataset folder {@code dir}. */
    public static DatasetIdentity of(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> yaml =
                    files.filter(f -> f.getFileName().toString().endsWith(".yaml"))
                            .filter(f -> !f.getFileName().toString().equals("fixtures.yaml"))
                            .sorted()
                            .toList();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String declared = null;
            for (Path f : yaml) {
                String text = Files.readString(f);
                sha.update(f.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update(text.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                if (f.getFileName().toString().equals("dataset.yaml")) declared = declaredIn(text);
            }
            return new DatasetIdentity(declared, HexFormat.of().formatHex(sha.digest()).substring(0, 12));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot read the dataset in " + dir + ": " + e.getMessage(), e);
        }
    }

    private static String declaredIn(String text) {
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("version:")) {
                String v = l.substring("version:".length()).strip().replaceAll("^[\"']|[\"']$", "");
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** One phrase for a report: {@code version 3 (a1b2c3d4e5f6)} or {@code a1b2c3d4e5f6 (no version declared)}. */
    public String describe() {
        return declared == null ? hash + " (no version declared)" : "version " + declared + " (" + hash + ")";
    }
}
