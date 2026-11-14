package io.github.llm4j.tools.openapi;

import io.github.llm4j.tools.HttpSupport;
import io.github.llm4j.tools.Limits;
import io.github.llm4j.tools.ToolRefusal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * What a tool returns for an HTTP answer (phase 36a, D5): the text, cut at a limit and saying so,
 * or, when the tool has a {@code save_dir}, the whole body saved to a file and a summary returned.
 *
 * <p>A saved file's name is made from the tool's name and a hash of the content, never from
 * anything the server sent, so a server cannot choose where a file goes and the same answer is
 * saved to the same place on a replay.
 */
final class Answers {

    /** Default for {@code max_bytes}: what a tool shows the model of one answer. */
    static final long DEFAULT_MAX_BYTES = 64L * 1024;

    /** The most {@code max_bytes} may be set to. */
    static final long CEILING_MAX_BYTES = 4L * 1024 * 1024;

    /** Default for {@code save_max_bytes}. */
    static final long DEFAULT_SAVE_BYTES = 16L * 1024 * 1024;

    /** The most {@code save_max_bytes} may be set to. */
    static final long CEILING_SAVE_BYTES = 128L * 1024 * 1024;

    /** How much of a saved text answer is shown inline. */
    static final int PREVIEW_BYTES = 2048;

    private static final int HASH_CHARS = 16;

    /** Extensions by content type; anything else is {@code bin}. */
    private static final Map<String, String> EXTENSIONS =
            Map.of(
                    "application/json", "json",
                    "text/plain", "txt",
                    "text/csv", "csv",
                    "application/xml", "xml",
                    "text/xml", "xml",
                    "application/pdf", "pdf",
                    "image/png", "png",
                    "image/jpeg", "jpg",
                    "image/gif", "gif");

    /**
     * How answers are shown and kept.
     *
     * @param maxBytes the most of one answer shown to the model
     * @param saveDir where the whole answer is kept when it is long or not text, or null
     * @param saveMaxBytes the most that is kept
     */
    record Settings(long maxBytes, Path saveDir, long saveMaxBytes) {
        /** How many bytes of the body are read at all. */
        long readLimit() {
            return saveDir == null ? maxBytes : Math.max(maxBytes, saveMaxBytes);
        }
    }

    private Answers() {}

    /**
     * The text for {@code reply}.
     *
     * @param tool the tool's name, which starts a saved file's name
     * @throws ToolRefusal when the answer is not text and there is nowhere to keep it
     */
    static String render(String tool, HttpSupport.Reply reply, Settings settings) {
        String head =
                "HTTP "
                        + reply.status()
                        + (reply.reason() == null || reply.reason().isEmpty()
                                ? ""
                                : " " + reply.reason());
        byte[] body = reply.body();
        if (body.length == 0) return head;
        String type = reply.contentType();
        boolean text = isTextual(type) || (isBlank(type) && readsAsText(body));
        boolean tooLong = body.length > settings.maxBytes();
        if (settings.saveDir() == null) {
            if (!text)
                throw new ToolRefusal(
                        head
                                + ": the response is "
                                + (isBlank(type) ? "of unknown type" : type)
                                + ", not text, so it isn't returned (save_dir: keeps such an answer in a"
                                + " file)");
            String shown = new String(body, StandardCharsets.UTF_8);
            if (reply.truncated()) shown += Limits.markerUnknownTotal(body.length);
            return head + "\nContent-Type: " + type + "\n\n" + shown;
        }
        if (text && !tooLong && !reply.truncated())
            return head + "\nContent-Type: " + type + "\n\n" + new String(body, StandardCharsets.UTF_8);
        Path saved = save(tool, type, body, settings.saveDir());
        StringBuilder out = new StringBuilder(head);
        out.append("\nContent-Type: ").append(type == null ? "unknown" : type);
        out.append("\n\nThe answer is ")
                .append(body.length)
                .append(reply.truncated() ? "+" : "")
                .append(" bytes and was saved to ")
                .append(saved);
        if (reply.truncated())
            out.append(
                    "; only the first "
                            + body.length
                            + " bytes (save_max_bytes) were kept, the rest was not read");
        out.append('.');
        if (text) {
            String preview = new String(body, StandardCharsets.UTF_8);
            out.append("\n\nThe start of it:\n").append(Limits.cut(preview, PREVIEW_BYTES));
        }
        return out.toString();
    }

    private static Path save(String tool, String type, byte[] body, Path dir) {
        String name =
                safe(tool) + "-" + hash(body) + "." + EXTENSIONS.getOrDefault(baseType(type), "bin");
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir.normalize()))
            throw new ToolRefusal("refused: the saved file would leave the save directory");
        try {
            Files.createDirectories(dir);
            try {
                Files.write(target, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException same) {
                // the same content under the same name: nothing to write
            }
        } catch (IOException e) {
            throw new ToolRefusal("could not save the answer: " + e.getClass().getSimpleName());
        }
        return target;
    }

    private static String safe(String tool) {
        String cleaned = tool.replaceAll("[^A-Za-z0-9_-]", "_");
        return cleaned.isEmpty() ? "answer" : cleaned;
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body))
                    .substring(0, HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static String baseType(String contentType) {
        return contentType == null
                ? ""
                : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    private static boolean isBlank(String type) {
        return type == null || type.isBlank();
    }

    /**
     * An answer that names no content type is taken as text when its bytes are: valid UTF-8 with no
     * control characters. Older services send plain bodies this way, and the tool this replaces
     * returned them.
     */
    private static boolean readsAsText(byte[] body) {
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(body))
                            .toString();
            return text.chars().noneMatch(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t');
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    /** Whether the content type is text a model can read. */
    static boolean isTextual(String contentType) {
        String t = baseType(contentType);
        return t.startsWith("text/")
                || t.equals("application/json")
                || t.equals("application/xml")
                || t.equals("application/x-www-form-urlencoded")
                || t.endsWith("+json")
                || t.endsWith("+xml");
    }
}
