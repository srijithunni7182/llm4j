package io.github.llm4j.a2a;

import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaKind;
import io.github.llm4j.model.MediaPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts files from outside through a stream the runtime owns. The destination is {@code
 * received/<run id>/<generated name>} inside the media root; the sender's name is only a label.
 * Bytes are counted as they arrive and the write stops at the policy's per-item limit. When the
 * stream is closed the type is decided from the bytes (an unknown type, SVG, or a kind other than
 * the one claimed is refused and the file removed). The file is written under a temporary name and
 * moved into place only on a clean close, so a failed write leaves nothing.
 */
public final class ReceivedFileSink {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final Path mediaRoot;
    private final String runId;
    private final MediaPolicy policy;
    private final int maxItems;
    private final List<ReceivedFile> files = new ArrayList<>();
    private int opened;

    /**
     * Creates a sink.
     *
     * @param mediaRoot the script's {@code media_root}, or null (then {@link #open} fails closed)
     * @param runId the run id; only letters, digits, hyphen and underscore
     * @param policy the media limits
     * @param maxItems the most files accepted
     */
    public ReceivedFileSink(Path mediaRoot, String runId, MediaPolicy policy, int maxItems) {
        this.mediaRoot = mediaRoot;
        this.runId = Objects.requireNonNull(runId, "runId");
        if (!SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException(
                    "runId may contain only letters, digits, '-' and '_' (it names a folder)");
        }
        this.policy = Objects.requireNonNull(policy, "policy");
        if (maxItems < 1) {
            throw new IllegalArgumentException("maxItems must be 1 or more");
        }
        this.maxItems = maxItems;
    }

    /**
     * Opens a stream for one file.
     *
     * @param label the sender's name for it; a label only
     * @param claimedMediaType what the sender says it is, or empty
     * @throws A2AException.Refused with no media root or when the file limit is reached
     */
    public synchronized OutputStream open(String label, String claimedMediaType) {
        if (mediaRoot == null) {
            throw new A2AException.Refused(
                    "this script has no media_root, so received files are refused; declare media_root to accept files");
        }
        if (opened >= maxItems) {
            throw new A2AException.Refused(
                    "at most "
                            + maxItems
                            + " received files are accepted for this task (media max_items)");
        }
        opened++;
        Path dir = mediaRoot.resolve("received").resolve(runId);
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve(".part-" + UUID.randomUUID());
            return new Stream(dir, temp, label, claimedMediaType == null ? "" : claimedMediaType);
        } catch (IOException e) {
            throw new A2AException.Refused(
                    "could not prepare the folder for received files: "
                            + e.getClass().getSimpleName());
        }
    }

    /** The files accepted so far. */
    public synchronized List<ReceivedFile> files() {
        return List.copyOf(files);
    }

    private synchronized void record(ReceivedFile file) {
        files.add(file);
    }

    private static Optional<MediaKind> claimedKind(String mediaType) {
        String type = mediaType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            return Optional.of(MediaKind.IMAGE);
        }
        if (type.startsWith("audio/")) {
            return Optional.of(MediaKind.AUDIO);
        }
        if (type.startsWith("video/")) {
            return Optional.of(MediaKind.VIDEO);
        }
        if (type.equals("application/pdf")) {
            return Optional.of(MediaKind.DOCUMENT);
        }
        return Optional.empty();
    }

    private final class Stream extends OutputStream {
        private final Path dir;
        private final Path temp;
        private final String label;
        private final String claimed;
        private final OutputStream out;
        private long written;
        private IOException failure;
        private boolean closed;

        Stream(Path dir, Path temp, String label, String claimed) throws IOException {
            this.dir = dir;
            this.temp = temp;
            this.label = label;
            this.claimed = claimed;
            this.out = Files.newOutputStream(temp);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("the stream is closed");
            }
            if (failure != null) {
                throw failure;
            }
            if (written + length > policy.maxBytesPerItem()) {
                fail(
                        "the file is larger than the "
                                + policy.maxBytesPerItem()
                                + " bytes the media policy allows");
                throw failure;
            }
            out.write(bytes, offset, length);
            written += length;
        }

        private void fail(String reason) {
            failure = new IOException("received file refused: " + reason);
            discard();
        }

        private void discard() {
            try {
                out.close();
            } catch (IOException ignored) {
                // the temporary file is removed next; nothing else depends on this stream
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                // leave it: a leftover is a '.part-' file the folder's owner can remove
                failure.addSuppressed(e);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (failure != null) {
                throw failure;
            }
            out.close();
            byte[] bytes = Files.readAllBytes(temp);
            Media media = classify(bytes);
            if (media == null) {
                Files.deleteIfExists(temp);
                throw failure;
            }
            Path target = dir.resolve("f-" + UUID.randomUUID());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            record(
                    new ReceivedFile(
                            target,
                            media.kind(),
                            media.mediaType(),
                            media.size(),
                            media.sha256(),
                            label));
        }

        private Media classify(byte[] bytes) {
            Media found = null;
            for (MediaKind kind : MediaKind.values()) {
                try {
                    found = Media.of(kind, bytes, label, policy);
                    break;
                } catch (InvalidRequestException notThisKind) {
                    // try the next kind; the bytes decide which one fits
                }
            }
            if (found == null) {
                failure =
                        new IOException(
                                "received file refused: the bytes are empty, not a recognised format, or not allowed by the media policy");
                return null;
            }
            Optional<MediaKind> claimedKind = claimedKind(claimed);
            if (claimedKind.isPresent() && claimedKind.get() != found.kind()) {
                failure =
                        new IOException(
                                "received file refused: the sender claimed "
                                        + claimed
                                        + " but the bytes are "
                                        + found.mediaType());
                return null;
            }
            return found;
        }
    }
}
