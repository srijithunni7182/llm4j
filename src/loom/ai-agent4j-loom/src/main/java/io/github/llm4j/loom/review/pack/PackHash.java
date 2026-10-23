package io.github.llm4j.loom.review.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A fingerprint of everything in a pack: the same files give the same hash on any machine, whatever
 * the line endings, and any change to any file changes it. It is what a project pins to know that
 * the pack it reviewed is the pack that runs.
 */
public final class PackHash {

    private PackHash() {}

    /** Lower case hexadecimal SHA-256 over the sorted paths and the text of every file. */
    public static String of(PackFiles files) {
        MessageDigest md = digest();
        for (String path : files.allFiles()) {
            String text = files.read(path).orElse("").replace("\r\n", "\n");
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(text.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
