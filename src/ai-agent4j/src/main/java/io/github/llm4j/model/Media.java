package io.github.llm4j.model;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.llm4j.exception.InvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An image, a sound, a video or a document to send a model beside the text of a message.
 *
 * <p>A {@code Media} is immutable and bounded. Its type is decided from its bytes, not from a name
 * or a label ({@link MediaSniffer}), and it is checked against a {@link MediaPolicy} when it is
 * made, so one that exists is one the library is willing to send. It keeps its own copy of the
 * bytes.
 *
 * <p>It never shows its content: {@link #toString()} is {@code media:image/png:182340B:ab12cd34}
 * (type, size, a short hash), JSON carries its {@link #descriptor()} only, and it is not {@link
 * java.io.Serializable}. The bytes leave it only through {@link #bytes()} and {@link #base64()},
 * which providers call when they build a request.
 */
public final class Media {

    private static final String DEFAULT_NAME = "media";
    private static final int MAX_NAME_LENGTH = 100;
    private static final int SHORT_HASH_LENGTH = 8;

    private final MediaKind kind;
    private final String mediaType;
    private final byte[] bytes;
    private final String name;
    private final String sha256;

    private Media(MediaKind kind, String mediaType, byte[] bytes, String name) {
        this.kind = kind;
        this.mediaType = mediaType;
        this.bytes = bytes;
        this.name = name;
        this.sha256 = hash(bytes);
    }

    /**
     * Makes a {@code Media} from bytes already in memory.
     *
     * @param kind what the caller says the bytes are; refused if they are something else
     * @param bytes the content, copied
     * @param name a display name; any directory part is dropped; may be null
     * @param policy the limits to hold it to
     * @throws InvalidRequestException if the bytes are empty, too large, not a format the library
     *     recognises, not the kind named, or not allowed by the policy
     */
    public static Media of(MediaKind kind, byte[] bytes, String name, MediaPolicy policy) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(policy, "policy");
        if (bytes.length == 0) {
            throw new InvalidRequestException("the " + kind.label() + " is empty");
        }
        if (bytes.length > policy.maxBytesPerItem()) {
            throw tooLarge(kind, bytes.length, policy);
        }
        MediaSniffer.Sniffed sniffed =
                MediaSniffer.sniff(bytes)
                        .orElseThrow(
                                () ->
                                        new InvalidRequestException(
                                                "the "
                                                        + kind.label()
                                                        + " is not a supported format; supported: "
                                                        + MediaSniffer.SUPPORTED.get(kind)));
        if (sniffed.kind() != kind) {
            throw new InvalidRequestException(
                    "given as "
                            + kind
                            + " but the bytes are "
                            + sniffed.mediaType()
                            + " ("
                            + sniffed.kind()
                            + "); give the kind the bytes really are");
        }
        if (!policy.allows(kind, sniffed.mediaType())) {
            throw new InvalidRequestException(
                    sniffed.mediaType()
                            + " is not allowed for "
                            + kind
                            + " by the media policy; allowed: "
                            + policy.allowedTypes().get(kind));
        }
        return new Media(kind, sniffed.mediaType(), bytes.clone(), cleanName(name));
    }

    /** As {@link #of}, reading a regular file; its file name, without directories, is the name. */
    public static Media fromFile(MediaKind kind, Path file, MediaPolicy policy) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(policy, "policy");
        String shown = String.valueOf(file.getFileName());
        if (!Files.isRegularFile(file)) {
            throw new InvalidRequestException(
                    shown + " is not a regular file, or does not exist; give the path of a file");
        }
        // Read one byte past the limit instead of trusting the size on disk, which can change
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes =
                    in.readNBytes(
                            Math.toIntExact(
                                    Math.min(policy.maxBytesPerItem() + 1, Integer.MAX_VALUE)));
        } catch (IOException e) {
            throw new InvalidRequestException("could not read " + shown + ": " + e.getMessage(), e);
        }
        if (bytes.length > policy.maxBytesPerItem()) {
            throw new InvalidRequestException(
                    shown
                            + " is larger than the "
                            + policy.maxBytesPerItem()
                            + " bytes the media policy allows for one "
                            + kind.label());
        }
        return of(kind, bytes, shown, policy);
    }

    /** An image from a file. */
    public static Media image(Path file, MediaPolicy policy) {
        return fromFile(MediaKind.IMAGE, file, policy);
    }

    /** A sound from a file. */
    public static Media audio(Path file, MediaPolicy policy) {
        return fromFile(MediaKind.AUDIO, file, policy);
    }

    /** A video from a file. */
    public static Media video(Path file, MediaPolicy policy) {
        return fromFile(MediaKind.VIDEO, file, policy);
    }

    /** A PDF from a file. */
    public static Media document(Path file, MediaPolicy policy) {
        return fromFile(MediaKind.DOCUMENT, file, policy);
    }

    /** What this is for. */
    public MediaKind kind() {
        return kind;
    }

    /** The type decided from its bytes, such as {@code image/png}. */
    public String mediaType() {
        return mediaType;
    }

    /** Its size in bytes. */
    public int size() {
        return bytes.length;
    }

    /** A display name: a file name with no directory and only printable characters. */
    public String name() {
        return name;
    }

    /** The SHA-256 of its bytes, in lower-case hex. */
    public String sha256() {
        return sha256;
    }

    /** A copy of its bytes. Only a provider building a request has a reason to ask. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Its bytes as base64, the form most providers take inline. */
    public String base64() {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** What may be written down about it instead of its bytes. */
    @JsonValue
    public MediaDescriptor descriptor() {
        return new MediaDescriptor(kind, mediaType, bytes.length, sha256, name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Media that)) return false;
        return kind == that.kind && mediaType.equals(that.mediaType) && sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, mediaType, sha256);
    }

    @Override
    public String toString() {
        return "media:"
                + mediaType
                + ":"
                + bytes.length
                + "B:"
                + sha256.substring(0, SHORT_HASH_LENGTH);
    }

    private static InvalidRequestException tooLarge(MediaKind kind, long size, MediaPolicy policy) {
        return new InvalidRequestException(
                "the "
                        + kind.label()
                        + " is "
                        + size
                        + " bytes, over the "
                        + policy.maxBytesPerItem()
                        + " the media policy allows for one item");
    }

    /** Keeps what follows the last slash, drops control characters and caps the length. */
    private static String cleanName(String given) {
        if (given == null) return DEFAULT_NAME;
        String base =
                given.substring(Math.max(given.lastIndexOf('/'), given.lastIndexOf('\\')) + 1);
        StringBuilder out = new StringBuilder();
        base.codePoints().filter(c -> !Character.isISOControl(c)).forEach(out::appendCodePoint);
        String cleaned = out.toString().strip();
        if (cleaned.isEmpty()) return DEFAULT_NAME;
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // every Java runtime must provide SHA-256
            throw new IllegalStateException("SHA-256 is not available in this runtime", e);
        }
    }
}
