package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.SecretStoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * A new master key in a file, for {@code weave secrets create --secrets-key-file} when the file
 * does not exist yet: random, and readable by its owner only where the file system can say so
 * (on Windows the file inherits the user profile's ACL). The key is never printed.
 */
final class MasterKeyFile {

    /** 32 random bytes: a 256-bit key, base64 so it is one line of text. */
    private static final int KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private MasterKeyFile() {}

    /** Writes a new key to {@code file}, making its folder; refuses to replace a file that exists. */
    static void make(Path file) {
        Path folder = file.toAbsolutePath().getParent();
        boolean posix = folder.getFileSystem().supportedFileAttributeViews().contains("posix");
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        byte[] line = (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            if (!Files.isDirectory(folder)) {
                if (posix) Files.createDirectories(folder, owner(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
                else Files.createDirectories(folder);
            }
            if (posix) Files.createFile(file, owner(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            else Files.createFile(file);
            Files.write(file, line, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            throw new SecretStoreException("the master key file " + file + " already exists; it is never replaced", e);
        } catch (IOException e) {
            throw new SecretStoreException("cannot make the master key file " + file + ": " + e.getMessage(), e);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
            java.util.Arrays.fill(line, (byte) 0);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> owner(Set<PosixFilePermission> permissions) {
        return PosixFilePermissions.asFileAttribute(permissions);
    }
}
