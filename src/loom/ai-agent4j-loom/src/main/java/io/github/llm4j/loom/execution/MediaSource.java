package io.github.llm4j.loom.execution;

import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.loom.ast.AttachItem;
import io.github.llm4j.model.Media;
import io.github.llm4j.model.MediaPolicy;
import io.github.llm4j.tools.PathGuard;
import io.github.llm4j.tools.SafePaths;
import io.github.llm4j.tools.ToolRefusal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Where a step's attachments come from: files under the script's {@code media_root}, and nowhere
 * else (docs/specs/31-multimodal-input R16).
 *
 * <p>A path is input from outside the script: it can be filled in from a variable that a person or
 * a model wrote. So the folder is itself confined to the script's own folder, and a path is
 * resolved with the same guard the {@code file} tool uses ({@link PathGuard}): no {@code ..}, no
 * absolute path, no link that leads out, no hidden file, no control character, nothing the run keeps
 * for itself. A file is then read with the library's own checks ({@link Media}): its type from its
 * bytes, within the size limits.
 *
 * <p>Messages name the path as the script wrote it, never an absolute path of this machine, and
 * never any of the file's content.
 */
final class MediaSource {

    private static final int SHOWN_PATH_LENGTH = 80;

    private final Path scriptDirectory;
    private final String mediaRoot;
    private final Set<Path> reserved;
    private final MediaPolicy policy;

    /**
     * @param scriptDirectory the folder the script is in
     * @param mediaRoot the script's {@code media_root} as written, or null for none
     * @param reserved files and folders the run keeps for itself, which are never attached
     * @param policy the size and count limits
     */
    MediaSource(Path scriptDirectory, String mediaRoot, Set<Path> reserved, MediaPolicy policy) {
        this.scriptDirectory = scriptDirectory;
        this.mediaRoot = mediaRoot;
        this.reserved = Set.copyOf(reserved);
        this.policy = policy;
    }

    /** The limits a script sets with {@code media { }}, over the library's defaults. */
    static MediaPolicy policyOf(io.github.llm4j.loom.ast.LoomScript script) {
        MediaPolicy.Builder policy = MediaPolicy.builder();
        if (script.getMediaMaxBytes() != null) policy.maxBytesPerItem(script.getMediaMaxBytes());
        if (script.getMediaMaxItems() != null) policy.maxItemsPerMessage(script.getMediaMaxItems());
        return policy.build();
    }

    /**
     * Checks the media folder alone: named, inside the script's folder, and there.
     *
     * @throws MediaRefusal if it is not
     */
    void checkRoot() {
        guardOfTheMediaRoot();
    }

    /**
     * Reads every attachment of a step, or refuses the whole step before any is sent.
     *
     * @param fillIn puts the values of variables into a path written as {@code "{name}.png"}
     * @throws MediaRefusal for any problem; nothing is sent and the step is not retried
     */
    List<Media> load(List<AttachItem> items, UnaryOperator<String> fillIn) {
        if (items.isEmpty()) return List.of();
        if (items.size() > policy.maxItemsPerMessage()) {
            throw new MediaRefusal(
                    items.size()
                            + " attachments, over the "
                            + policy.maxItemsPerMessage()
                            + " the media settings allow per task; attach fewer or raise media {"
                            + " max_items }");
        }
        PathGuard guard = guardOfTheMediaRoot();
        List<Media> loaded = new ArrayList<>();
        long total = 0;
        for (AttachItem item : items) {
            String given = fillIn.apply(item.path());
            Media media = read(item, guard, given);
            total += media.size();
            if (total > policy.maxBytesPerRequest()) {
                throw new MediaRefusal(
                        "the attachments are "
                                + total
                                + " bytes together, over the "
                                + policy.maxBytesPerRequest()
                                + " the media settings allow per task");
            }
            loaded.add(media);
        }
        return List.copyOf(loaded);
    }

    private PathGuard guardOfTheMediaRoot() {
        if (mediaRoot == null) {
            throw new MediaRefusal(
                    "this step attaches files, but the script has no media_root; add a line such as"
                            + " media_root: \"./assets\" at the top of the script");
        }
        Path root;
        try {
            // the folder must itself be inside the script's folder, or "media_root: /" would make
            // the whole disk available to every path a variable can name
            root = SafePaths.inside(scriptDirectory, mediaRoot);
        } catch (IllegalArgumentException e) {
            throw new MediaRefusal(
                    "media_root \""
                            + shown(mediaRoot)
                            + "\" must be a folder inside the script's folder",
                    e);
        }
        if (!Files.isDirectory(root)) {
            throw new MediaRefusal(
                    "media_root \""
                            + shown(mediaRoot)
                            + "\" is not a folder that exists next to the script");
        }
        return new PathGuard(root, reserved);
    }

    private Media read(AttachItem item, PathGuard guard, String given) {
        String label = "attach " + item.kind().label() + " \"" + shown(given) + "\": ";
        Path file;
        try {
            file = guard.resolve(given);
        } catch (ToolRefusal e) {
            throw new MediaRefusal(label + e.getMessage().replaceFirst("^refused: ", ""), e);
        }
        if (!Files.exists(file)) {
            throw new MediaRefusal(
                    label + "there is no such file in the media folder (a missing file is not retried)");
        }
        try {
            return Media.fromFile(item.kind(), file, policy);
        } catch (InvalidRequestException e) {
            throw new MediaRefusal(label + e.getMessage(), e);
        }
    }

    /** The path for a message: printable characters only, and not too long. */
    private static String shown(String path) {
        StringBuilder out = new StringBuilder();
        path.codePoints()
                .map(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? '?' : c)
                .forEach(out::appendCodePoint);
        return out.length() > SHOWN_PATH_LENGTH
                ? out.substring(0, SHOWN_PATH_LENGTH) + "…"
                : out.toString();
    }
}
