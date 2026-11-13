package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * A directory an agent reads and edits (phase 35, D11): read, list, exists, write, append, patch,
 * find, grep, diff, snapshot and restore, with {@code allow} required, protected paths, a deny list
 * {@code allow} cannot override, and no link ever followed. Every result is a JSON object.
 */
final class WorkspaceTool extends GenericTool {

    enum Action {
        READ,
        LIST,
        EXISTS,
        WRITE,
        APPEND,
        PATCH,
        FIND,
        GREP,
        DIFF,
        SNAPSHOT,
        RESTORE;

        boolean changes() {
            return this == WRITE
                    || this == APPEND
                    || this == PATCH
                    || this == SNAPSHOT
                    || this == RESTORE;
        }

        /** Safe to repeat after an unknown outcome: the same call leaves the same state. */
        boolean idempotent() {
            return this == SNAPSHOT || this == RESTORE;
        }

        boolean allowedIn(Mode mode) {
            return switch (mode) {
                case READ -> this == READ
                        || this == LIST
                        || this == EXISTS
                        || this == FIND
                        || this == GREP
                        || this == DIFF;
                case WRITE -> this == WRITE
                        || this == APPEND
                        || this == PATCH
                        || this == EXISTS
                        || this == SNAPSHOT
                        || this == RESTORE;
                case READWRITE -> true;
            };
        }
    }

    enum Mode {
        READ,
        WRITE,
        READWRITE
    }

    /** A workspace tool's declaration, parsed and checked. */
    record Config(
            Mode mode,
            Globs allow,
            Globs protect,
            Globs ignore,
            boolean overwrite,
            long maxBytes,
            int maxFiles,
            long maxSnapshotBytes,
            int maxMatches,
            EffectPolicy.OnUnknown onUnknown) {}

    static final int MAX_REGEX = 200;
    static final int MAX_LINE = 2000;
    static final int MAX_SHOWN = 500;
    static final int MAX_CONTEXT = 5;
    static final long REGEX_NANOS = 2_000_000_000L;
    private static final Pattern READ_DENIED = Pattern.compile("\\.env.*|.*\\.pem|.*\\.key|id_.*");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Config config;
    private final WorkspaceJail jail;
    private final SnapshotStore snapshots;
    private final Path store;

    WorkspaceTool(
            String name,
            Config config,
            Path root,
            Path store,
            int maxSnapshots,
            EffectContext context) {
        super(name, "workspace", description(config), Redactor.NONE, context);
        this.config = config;
        java.util.Set<Path> reserved = new java.util.HashSet<>(context.reservedPaths());
        reserved.add(store.toAbsolutePath().normalize());
        this.jail = new WorkspaceJail(root, reserved);
        this.store = store;
        this.snapshots = new SnapshotStore(store, maxSnapshots);
    }

    private static String description(Config c) {
        List<String> actions = new ArrayList<>();
        for (Action a : Action.values())
            if (a.allowedIn(c.mode())) actions.add(a.name().toLowerCase(Locale.ROOT));
        return "Reads and edits the files of one workspace directory. Give action and, where it applies, path"
                + " (relative to the workspace). Actions: "
                + String.join(", ", actions)
                + ". read takes from_line and lines and returns a version; write and append take content;"
                + " patch takes edits, a list of {old, new} where each old text must appear exactly once,"
                + " and an optional version; find takes pattern (a glob); grep takes text (or regex),"
                + " an optional path glob and context; diff, snapshot and restore take name. Only paths"
                + " matching "
                + String.join(", ", c.allow().patterns())
                + " can be read or written"
                + (c.protect().isEmpty()
                        ? ""
                        : "; " + String.join(", ", c.protect().patterns()) + " are read only")
                + ".";
    }

    // ---- the effect protocol

    @Override
    public boolean isEffect(Map<String, Object> args) {
        Action a = actionOf(args);
        return a != null && a.changes();
    }

    @Override
    public EffectPolicy policy() {
        return new EffectPolicy(config.onUnknown(), false, 0);
    }

    @Override
    public EffectPolicy policy(Map<String, Object> args) {
        Action a = actionOf(args);
        return a != null && a.idempotent()
                ? new EffectPolicy(EffectPolicy.OnUnknown.SKIP, true, 0)
                : policy();
    }

    @Override
    public String target(Map<String, Object> args) {
        Object what = args.get("path") != null ? args.get("path") : args.get("name");
        return args.get("action")
                + (what == null ? "" : " " + Limits.excerpt(String.valueOf(what), 120));
    }

    private static Action actionOf(Map<String, Object> args) {
        Object a = args.get("action");
        if (a == null) return null;
        try {
            return Action.valueOf(String.valueOf(a).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws IOException {
        Action action = actionOf(args);
        if (action == null)
            throw new ToolRefusal(
                    "action must be one of read, list, exists, write, append, patch, find, grep, diff, snapshot, restore");
        if (!action.allowedIn(config.mode()))
            throw new ToolRefusal(
                    action.name().toLowerCase(Locale.ROOT)
                            + " is not available here (this tool is "
                            + config.mode().name().toLowerCase(Locale.ROOT)
                            + " only)");
        Map<String, Object> out =
                switch (action) {
                    case READ -> read(args);
                    case LIST -> list(args);
                    case EXISTS -> Map.of(
                            "exists",
                            Files.exists(
                                    jail.resolve(text(args, "path")), LinkOption.NOFOLLOW_LINKS));
                    case WRITE -> write(args, false);
                    case APPEND -> write(args, true);
                    case PATCH -> patch(args);
                    case FIND -> find(args);
                    case GREP -> grep(args);
                    case DIFF -> diff(args);
                    case SNAPSHOT -> snapshot(args);
                    case RESTORE -> restore(args);
                };
        return JSON.writeValueAsString(out);
    }

    // ---- rules on paths

    private Path readable(String given) {
        Path p = jail.resolve(given);
        String relative = jail.relative(p);
        requireAllowed(relative, given);
        if (READ_DENIED.matcher(p.getFileName().toString()).matches())
            throw new ToolRefusal(
                    "refused: "
                            + given
                            + " is not readable (keys and environment files never are)");
        return p;
    }

    private Path writable(String given) {
        Path p = jail.resolve(given);
        String relative = jail.relative(p);
        requireAllowed(relative, given);
        for (String segment : relative.split("/"))
            if (segment.equals(".git"))
                throw new ToolRefusal(
                        "refused: nothing under .git may be written (a hook there would run on the host)");
        if (config.protect().matches(relative))
            throw new ToolRefusal("refused: " + given + " is protected (read only)");
        return p;
    }

    private void requireAllowed(String relative, String given) {
        if (!config.allow().matches(relative))
            throw new ToolRefusal(
                    "refused: "
                            + given
                            + " doesn't match allow ("
                            + String.join(", ", config.allow().patterns())
                            + ")");
    }

    // ---- reading

    private Map<String, Object> read(Map<String, Object> args) throws IOException {
        String given = text(args, "path");
        Path file = readable(given);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new ToolRefusal(given + " doesn't exist or isn't a file");
        long size = Files.size(file);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes((int) Math.min(size, config.maxBytes()));
        }
        if (binary(bytes)) throw new ToolRefusal(given + " is not text (" + size + " bytes)");
        String text = new String(bytes, StandardCharsets.UTF_8);
        int from = number(args, "from_line", 1);
        Integer lines = args.get("lines") == null ? null : number(args, "lines", 1);
        if (from > 1 || lines != null) {
            List<String> all = text.lines().toList();
            int start = Math.min(all.size(), from - 1);
            int end = lines == null ? all.size() : Math.min(all.size(), start + lines);
            text = String.join("\n", all.subList(start, end));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", given);
        out.put("version", SnapshotStore.sha256(file).substring(0, 12));
        out.put("content", text);
        out.put("truncated", size > config.maxBytes());
        return out;
    }

    private static boolean binary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) if (bytes[i] == 0) return true;
        return false;
    }

    private Map<String, Object> list(Map<String, Object> args) throws IOException {
        String given = optionalText(args, "path");
        Path dir = jail.resolve(given == null ? "." : given);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new ToolRefusal("that location isn't a directory");
        List<String> entries = new ArrayList<>();
        try (Stream<Path> all = Files.list(dir)) {
            for (Path p : all.sorted().toList()) {
                if (Files.isSymbolicLink(p) || jail.isReserved(p)) continue;
                String relative = jail.relative(p);
                boolean directory = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                if (directory
                        ? config.ignore().coversDirectory(relative)
                        : config.ignore().matches(relative)) continue;
                entries.add(p.getFileName() + (directory ? "/" : ""));
            }
        }
        return bounded("entries", entries);
    }

    private Map<String, Object> bounded(String key, List<?> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean cut = items.size() > config.maxMatches();
        out.put(key, cut ? items.subList(0, config.maxMatches()) : items);
        out.put("truncated", cut);
        return out;
    }

    /** The regular files under the root, in path order, with the links found among them. */
    private record Tree(Map<String, Path> files, List<String> links) {}

    private Tree tree(boolean withProtected) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        List<String> links = new ArrayList<>();
        Path root = jail.root();
        if (!Files.isDirectory(root)) return new Tree(files, links);
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) return FileVisitResult.CONTINUE;
                        String relative = jail.relative(dir);
                        if (jail.isReserved(dir)
                                || dir.startsWith(store)
                                || config.ignore().coversDirectory(relative))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String relative = jail.relative(file);
                        if (jail.isReserved(file) || config.ignore().matches(relative))
                            return FileVisitResult.CONTINUE;
                        if (!withProtected && config.protect().matches(relative))
                            return FileVisitResult.CONTINUE;
                        if (attrs.isSymbolicLink()) links.add(relative);
                        else if (attrs.isRegularFile()) files.put(relative, file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        return new Tree(files, links);
    }

    private Map<String, Object> find(Map<String, Object> args) throws IOException {
        Globs pattern = new Globs(List.of(text(args, "pattern")));
        List<String> paths = new ArrayList<>();
        for (String relative : tree(true).files().keySet())
            if (pattern.matches(relative)) paths.add(relative);
        return bounded("paths", paths);
    }

    private Map<String, Object> grep(Map<String, Object> args) throws IOException {
        String literal = optionalText(args, "text");
        String regex = optionalText(args, "regex");
        if ((literal == null) == (regex == null))
            throw new ToolRefusal("grep takes text (a literal) or regex, one of them");
        Pattern pattern = null;
        if (regex != null) {
            if (regex.length() > MAX_REGEX)
                throw new ToolRefusal("the regex is longer than " + MAX_REGEX + " characters");
            try {
                pattern = Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new ToolRefusal("not a valid regex: " + e.getDescription());
            }
        }
        String scope = optionalText(args, "path");
        Globs within = scope == null ? null : new Globs(List.of(scope));
        int context =
                Math.min(MAX_CONTEXT, args.get("context") == null ? 0 : number(args, "context", 0));
        long deadline = System.nanoTime() + REGEX_NANOS;
        List<Map<String, Object>> matches = new ArrayList<>();
        boolean cut = false;
        for (Map.Entry<String, Path> f : tree(true).files().entrySet()) {
            String relative = f.getKey();
            if (within != null && !within.matches(relative)) continue;
            if (!config.allow().matches(relative)
                    || READ_DENIED.matcher(f.getValue().getFileName().toString()).matches())
                continue;
            if (Files.size(f.getValue()) > config.maxBytes()) continue;
            byte[] bytes = Files.readAllBytes(f.getValue());
            if (binary(bytes)) continue;
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.length() > MAX_LINE) line = line.substring(0, MAX_LINE);
                boolean hit =
                        pattern == null ? line.contains(literal) : found(pattern, line, deadline);
                if (!hit) continue;
                if (matches.size() >= config.maxMatches()) {
                    cut = true;
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", relative);
                m.put("line", i + 1);
                m.put("text", shown(line));
                if (context > 0) {
                    m.put("before", window(lines, Math.max(0, i - context), i));
                    m.put("after", window(lines, i + 1, Math.min(lines.size(), i + 1 + context)));
                }
                matches.add(m);
            }
            if (cut) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matches", matches);
        out.put("truncated", cut);
        return out;
    }

    private static List<String> window(List<String> lines, int from, int to) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(shown(lines.get(i)));
        return out;
    }

    private static String shown(String line) {
        return line.length() > MAX_SHOWN ? line.substring(0, MAX_SHOWN) + "…" : line;
    }

    /**
     * A regex search that gives up when the whole grep has run too long (a catastrophic pattern).
     */
    private static boolean found(Pattern pattern, String line, long deadline) {
        try {
            return pattern.matcher(new Deadline(line, deadline)).find();
        } catch (Deadline.Expired e) {
            throw new ToolRefusal("the regex took too long; make it simpler");
        }
    }

    /** A line that stops a regex engine reading it once the deadline has passed. */
    private record Deadline(CharSequence text, long deadline) implements CharSequence {
        static final class Expired extends RuntimeException {
            Expired() {
                super(null, null, false, false);
            }
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) throw new Expired();
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    // ---- writing

    private Map<String, Object> write(Map<String, Object> args, boolean append) throws IOException {
        String given = text(args, "path");
        Path file = writable(given);
        String content = args.get("content") instanceof String s ? s : text(args, "content");
        byte[] bytes =
                (append && !content.endsWith("\n") ? content + "\n" : content)
                        .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > config.maxBytes())
            throw new ToolRefusal(
                    "content is larger than " + config.maxBytes() + " bytes (max_bytes)");
        ReentrantLock lock = LOCKS.computeIfAbsent(file, p -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            jail.resolve(given); // the directories just made are still no links
            if (append) {
                long existing =
                        Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? Files.size(file) : 0;
                byte[] prefix =
                        existing > 0 && !endsWithNewline(file) ? new byte[] {'\n'} : new byte[0];
                if (existing + prefix.length + bytes.length > config.maxBytes())
                    throw new ToolRefusal(
                            given + " would grow past " + config.maxBytes() + " bytes (max_bytes)");
                Files.write(
                        file,
                        concat(prefix, bytes),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        LinkOption.NOFOLLOW_LINKS);
            } else {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !config.overwrite())
                    throw new ToolRefusal(
                            given
                                    + " already exists, and this tool may not replace files (overwrite: true)");
                replace(file, bytes);
            }
        } finally {
            lock.unlock();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", given);
        out.put("version", SnapshotStore.sha256(file).substring(0, 12));
        out.put("bytes", bytes.length);
        return out;
    }

    private Map<String, Object> patch(Map<String, Object> args) throws IOException {
        String given = text(args, "path");
        Path file = writable(given);
        List<PatchApplier.Edit> edits = edits(args.get("edits"));
        ReentrantLock lock = LOCKS.computeIfAbsent(file, p -> new ReentrantLock());
        lock.lock();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new ToolRefusal(given + " doesn't exist or isn't a file");
            if (Files.size(file) > config.maxBytes())
                throw new ToolRefusal(
                        given + " is larger than " + config.maxBytes() + " bytes (max_bytes)");
            byte[] original = Files.readAllBytes(file);
            if (binary(original)) throw new ToolRefusal(given + " is not text");
            String version = optionalText(args, "version");
            if (version != null && !SnapshotStore.sha256(original).startsWith(version))
                throw new ToolRefusal(
                        "the version of "
                                + given
                                + " is not "
                                + version
                                + ": read it again and patch that");
            String changed =
                    PatchApplier.apply(new String(original, StandardCharsets.UTF_8), edits);
            byte[] bytes = changed.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > config.maxBytes())
                throw new ToolRefusal(
                        given + " would grow past " + config.maxBytes() + " bytes (max_bytes)");
            replace(file, bytes);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", given);
            out.put("version", SnapshotStore.sha256(bytes).substring(0, 12));
            out.put("edits", edits.size());
            return out;
        } finally {
            lock.unlock();
        }
    }

    private static List<PatchApplier.Edit> edits(Object given) {
        if (!(given instanceof List<?> list))
            throw new ToolRefusal("edits: give a list of {old, new}");
        List<PatchApplier.Edit> out = new ArrayList<>();
        int n = 0;
        for (Object item : list) {
            n++;
            if (!(item instanceof Map<?, ?> m)
                    || !(m.get("old") instanceof String old)
                    || !(m.get("new") instanceof String now))
                throw new ToolRefusal("edit " + n + ": give old and new, both text");
            out.add(new PatchApplier.Edit(old, now));
        }
        return out;
    }

    private static void replace(Path file, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".workspace-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        try (var channel = Files.newByteChannel(file)) {
            channel.position(channel.size() - 1);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            channel.read(b);
            return b.get(0) == '\n';
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ---- snapshots

    private Map<String, Object> snapshot(Map<String, Object> args) throws IOException {
        String name = SnapshotStore.checkName(optionalText(args, "name"));
        Tree tree = tree(false);
        if (!tree.links().isEmpty()) {
            List<String> links = tree.links().stream().sorted().limit(5).toList();
            throw new ToolRefusal(
                    "the workspace holds links ("
                            + String.join(", ", links)
                            + (tree.links().size() > links.size() ? ", …" : "")
                            + "); links are not followed or stored, so remove them before a snapshot");
        }
        long bytes = 0;
        for (Path f : tree.files().values()) bytes += Files.size(f);
        if (tree.files().size() > config.maxFiles() || bytes > config.maxSnapshotBytes())
            throw new ToolRefusal(
                    "the workspace is larger than the snapshot cap ("
                            + tree.files().size()
                            + " files, "
                            + bytes
                            + " bytes; max_files "
                            + config.maxFiles()
                            + ", max_snapshot_bytes "
                            + config.maxSnapshotBytes()
                            + "); ignore a path or raise the cap");
        return SnapshotStore.describe(snapshots.save(name, tree.files()));
    }

    private Map<String, Object> restore(Map<String, Object> args) throws IOException {
        SnapshotStore.Manifest m =
                snapshots.load(SnapshotStore.checkName(optionalText(args, "name")));
        Map<String, Path> stored = new TreeMap<>();
        for (Map.Entry<String, SnapshotStore.Stored> f : m.files().entrySet())
            stored.put(f.getKey(), snapshots.verified(f.getKey(), f.getValue()));
        Tree now = tree(false);
        int deleted = 0;
        for (String link : now.links()) {
            Files.delete(jail.root().resolve(link));
            if (!m.files().containsKey(link)) deleted++;
        }
        for (String relative : now.files().keySet())
            if (!m.files().containsKey(relative)) {
                Files.delete(now.files().get(relative));
                deleted++;
            }
        int restored = 0;
        for (Map.Entry<String, SnapshotStore.Stored> f : m.files().entrySet()) {
            Path target = jail.root().resolve(f.getKey());
            unlinkParents(target);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && f.getValue().sha().equals(SnapshotStore.sha256(target))) continue;
            Files.createDirectories(target.getParent());
            replace(target, Files.readAllBytes(stored.get(f.getKey())));
            restored++;
        }
        removeEmptyDirectories(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", m.name());
        out.put("restored", restored);
        out.put("deleted", deleted);
        return out;
    }

    /**
     * A directory on the way to a file that is now a link is removed, so the write stays inside.
     */
    private void unlinkParents(Path target) throws IOException {
        Path current = jail.root();
        for (Path segment :
                jail.root()
                        .relativize(
                                target.getParent() == null ? jail.root() : target.getParent())) {
            if (segment.toString().isEmpty()) continue;
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) Files.delete(current);
        }
    }

    /** Directories that hold nothing and no snapshot file needs, made since, are removed. */
    private void removeEmptyDirectories(SnapshotStore.Manifest m) throws IOException {
        TreeSet<String> needed = new TreeSet<>();
        for (String f : m.files().keySet())
            for (Path p = Path.of(f).getParent(); p != null; p = p.getParent())
                needed.add(p.toString().replace('\\', '/'));
        List<Path> dirs = new ArrayList<>();
        Files.walkFileTree(
                jail.root(),
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(jail.root())) return FileVisitResult.CONTINUE;
                        String relative = jail.relative(dir);
                        if (jail.isReserved(dir)
                                || dir.startsWith(store)
                                || config.ignore().coversDirectory(relative)
                                || config.protect().coversDirectory(relative))
                            return FileVisitResult.SKIP_SUBTREE;
                        dirs.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
        for (int i = dirs.size() - 1; i >= 0; i--) {
            Path d = dirs.get(i);
            if (needed.contains(jail.relative(d))) continue;
            try (Stream<Path> inside = Files.list(d)) {
                if (inside.findAny().isEmpty()) Files.delete(d);
            }
        }
    }

    private Map<String, Object> diff(Map<String, Object> args) throws IOException {
        SnapshotStore.Manifest m = snapshots.load(optionalText(args, "name"));
        Tree now = tree(false);
        List<String> added = new ArrayList<>(now.links());
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, Path> f : now.files().entrySet()) {
            SnapshotStore.Stored s = m.files().get(f.getKey());
            if (s == null) added.add(f.getKey());
            else if (!s.sha().equals(SnapshotStore.sha256(f.getValue()))) changed.add(f.getKey());
        }
        for (String relative : m.files().keySet())
            if (!now.files().containsKey(relative)) deleted.add(relative);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshot", m.name());
        int cap = config.maxMatches();
        out.put("added", added.size() > cap ? added.subList(0, cap) : added);
        out.put("changed", changed.size() > cap ? changed.subList(0, cap) : changed);
        out.put("deleted", deleted.size() > cap ? deleted.subList(0, cap) : deleted);
        out.put("truncated", added.size() > cap || changed.size() > cap || deleted.size() > cap);
        return out;
    }

    // ---- arguments

    private static int number(Map<String, Object> args, String key, int min) {
        Object v = args.get(key);
        if (v == null) return min;
        try {
            int n =
                    v instanceof Number num
                            ? num.intValue()
                            : Integer.parseInt(String.valueOf(v).trim());
            if (n < min) throw new ToolRefusal(key + " must be " + min + " or more");
            return n;
        } catch (NumberFormatException e) {
            throw new ToolRefusal(key + " must be a whole number");
        }
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema =
                new LinkedHashMap<>(
                        io.github.llm4j.model.ToolSchema.object()
                                .enumeration(
                                        "action",
                                        "What to do",
                                        true,
                                        List.of(
                                                "read",
                                                "list",
                                                "exists",
                                                "write",
                                                "append",
                                                "patch",
                                                "find",
                                                "grep",
                                                "diff",
                                                "snapshot",
                                                "restore"))
                                .string(
                                        "path",
                                        "A path relative to the workspace (a glob for grep)",
                                        false)
                                .string("content", "The text to write or append", false)
                                .integer("from_line", "First line to read", false)
                                .integer("lines", "How many lines to read", false)
                                .string(
                                        "version",
                                        "The version read returned, to patch only that",
                                        false)
                                .string("pattern", "A glob of paths (find)", false)
                                .string("text", "Text to look for (grep)", false)
                                .string("regex", "A regular expression to look for (grep)", false)
                                .integer(
                                        "context",
                                        "Lines around each match (grep, at most 5)",
                                        false)
                                .string("name", "A snapshot's name", false)
                                .build());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        properties.put(
                "edits",
                Map.of(
                        "type", "array",
                        "description", "For patch: each old text must appear exactly once",
                        "items",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "old",
                                                        Map.of("type", "string"),
                                                        "new",
                                                        Map.of("type", "string")),
                                        "required", List.of("old", "new"))));
        schema.put("properties", properties);
        return schema;
    }
}
