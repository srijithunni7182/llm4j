package io.github.llm4j.tools;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.EffectPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads, lists, writes and appends text files under a root directory. */
final class FileTool extends GenericTool {

    private static final long MAX_WRITE_BYTES = 1024 * 1024;
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_LIST = 500;
    private static final List<String> DEFAULT_ALLOW = List.of("*.md", "*.txt", "*.json", "*.jsonl", "*.csv", "*.log");

    enum Mode { READ, WRITE, READWRITE }

    enum Action {
        READ, LIST, EXISTS, WRITE, APPEND;

        boolean changes() {
            return this == WRITE || this == APPEND;
        }
    }

    /** A file tool's declaration, parsed and checked. */
    record Config(String root, Mode mode, List<String> allow, boolean overwrite, long maxBytes, EffectPolicy.OnUnknown onUnknown) {

        static Config parse(Options o) {
            Mode mode = Mode.valueOf(o.choice("mode", "read", "read", "write", "readwrite").toUpperCase(Locale.ROOT));
            List<String> allow = o.has("allow") ? o.list("allow") : DEFAULT_ALLOW;
            if (allow.isEmpty()) throw new OptionException("allow: needs at least one file name pattern, e.g. \"*.md, *.txt\"");
            return new Config(o.string("root", "."), mode, allow, o.bool("overwrite", false),
                    o.size("max_bytes", 256 * 1024, 8L * 1024 * 1024),
                    EffectPolicy.parse(o.choice("on_unknown", "skip", "skip", "retry")));
        }
    }

    /** One lock per file, so parallel branches appending to the same file don't interleave. */
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Config config;
    private final Path root;
    private final EffectContext context;
    private final PathGuard guard;
    private final List<PathMatcher> allowed;

    FileTool(String name, Config config, Path root, EffectContext context) {
        super(name, "file", description(config), Redactor.NONE, context);
        this.config = config;
        this.root = root;
        this.context = context;
        this.guard = new PathGuard(root, context.reservedPaths());
        this.allowed = config.allow().stream().map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p)).toList();
    }

    private static String description(Config c) {
        List<String> actions = new ArrayList<>();
        if (c.mode() != Mode.WRITE) actions.add("read (path; optional from_line and lines), list (optional path, pattern)");
        actions.add("exists (path)");
        if (c.mode() != Mode.READ) {
            actions.add("write (path, content" + (c.overwrite() ? "; replaces an existing file" : "; fails if the file exists") + ")");
            actions.add("append (path, content; adds a newline)");
        }
        return "Works with text files in one directory. Give action and path (relative to that directory). Actions: "
                + String.join("; ", actions) + ". Only files matching " + String.join(", ", c.allow())
                + " can be used. Hidden files are not available. Text is cut at " + c.maxBytes() + " bytes.";
    }

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
    public String target(Map<String, Object> args) {
        Object p = args.get("path");
        return String.valueOf(args.get("action")) + " " + (p == null ? "." : Limits.excerpt(String.valueOf(p), 120));
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
        if (action == null) throw new ToolRefusal("action must be one of read, list, exists, write, append");
        requireMode(action);
        String given = optionalText(args, "path");
        if (given == null && action == Action.LIST) given = ".";
        if (given == null) throw new ToolRefusal("path is required");
        Path target = guard.resolve(given);
        return switch (action) {
            case READ -> read(target, given, args);
            case LIST -> list(target, args);
            case EXISTS -> String.valueOf(Files.exists(target));
            case WRITE -> write(target, given, text(args, "content"));
            case APPEND -> append(target, given, text(args, "content"));
        };
    }

    private void requireMode(Action action) {
        boolean ok = switch (config.mode()) {
            case READ -> action == Action.READ || action == Action.LIST || action == Action.EXISTS;
            case WRITE -> action == Action.WRITE || action == Action.APPEND || action == Action.EXISTS;
            case READWRITE -> true;
        };
        if (!ok) throw new ToolRefusal(action.name().toLowerCase(Locale.ROOT) + " is not available here (this tool is " + config.mode().name().toLowerCase(Locale.ROOT) + " only)");
    }

    // ── Confinement ──────────────────────────────────────────────────────────────────────────

    private void requireAllowedName(Path file, String given) {
        Path name = file.getFileName();
        if (name == null || allowed.stream().noneMatch(m -> m.matches(name))) {
            throw new ToolRefusal("refused: " + given + " doesn't match the allowed names " + String.join(", ", config.allow()));
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────────────────────

    private String read(Path file, String given, Map<String, Object> args) throws IOException {
        requireAllowedName(file, given);
        if (!Files.isRegularFile(file)) throw new ToolRefusal(given + " doesn't exist or isn't a file");
        long size = Files.size(file);
        Limits.Capped capped;
        try (InputStream in = Files.newInputStream(file)) {
            capped = Limits.readCapped(in, config.maxBytes());
        }
        for (int i = 0; i < Math.min(capped.bytes().length, 8192); i++) {
            if (capped.bytes()[i] == 0) throw new ToolRefusal(given + " is not a text file");
        }
        String text = capped.text();
        int from = integerArg(args, "from_line", 1);
        Integer lines = args.get("lines") == null ? null : integerArg(args, "lines", 1);
        if (from > 1 || lines != null) text = window(text, from, lines);
        return capped.truncated() ? text + Limits.marker(capped.bytes().length, size) : text;
    }

    private static String window(String text, int from, Integer count) {
        List<String> all = text.lines().toList();
        int start = Math.max(0, from - 1);
        int end = count == null ? all.size() : Math.min(all.size(), start + count);
        return start >= all.size() ? "" : String.join("\n", all.subList(start, end));
    }

    private String list(Path dir, Map<String, Object> args) throws IOException {
        if (!Files.isDirectory(dir)) throw new ToolRefusal("that location isn't a directory");
        PathMatcher filter = optionalText(args, "pattern") == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + optionalText(args, "pattern"));
        List<String> names;
        try (Stream<Path> entries = Files.list(dir)) {
            names = entries.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !guard.isReserved(p))
                    .filter(p -> Files.isDirectory(p) || allowed.stream().anyMatch(m -> m.matches(p.getFileName())))
                    .filter(p -> filter == null || filter.matches(p.getFileName()))
                    .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : ""))
                    .sorted().collect(Collectors.toList());
        }
        if (names.isEmpty()) return "(empty)";
        String out = String.join("\n", names.size() > MAX_LIST ? names.subList(0, MAX_LIST) : names);
        return names.size() > MAX_LIST ? out + "\n… [" + (names.size() - MAX_LIST) + " more not shown]" : out;
    }

    private String write(Path file, String given, String content) throws IOException {
        requireAllowedName(file, given);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) throw new ToolRefusal("content is too large (" + bytes.length + " bytes; the limit is " + MAX_WRITE_BYTES + ")");
        ReentrantLock lock = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.exists(file) && !config.overwrite()) throw new ToolRefusal(given + " already exists, and this tool may not replace files");
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".loom-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            lock.unlock();
        }
        return "Wrote " + bytes.length + " bytes to " + given + ".";
    }

    private String append(Path file, String given, String content) throws IOException {
        requireAllowedName(file, given);
        byte[] added = (content.endsWith("\n") ? content : content + "\n").getBytes(StandardCharsets.UTF_8);
        if (added.length > MAX_WRITE_BYTES) throw new ToolRefusal("content is too large (" + added.length + " bytes; the limit is " + MAX_WRITE_BYTES + ")");
        ReentrantLock lock = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            long existing = Files.exists(file) ? Files.size(file) : 0;
            if (existing + added.length > MAX_FILE_BYTES) throw new ToolRefusal(given + " would grow past " + MAX_FILE_BYTES + " bytes");
            byte[] prefix = existing > 0 && !endsWithNewline(file, existing) ? new byte[] {'\n'} : new byte[0];
            byte[] all = new byte[prefix.length + added.length];
            System.arraycopy(prefix, 0, all, 0, prefix.length);
            System.arraycopy(added, 0, all, prefix.length, added.length);
            Files.write(file, all, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally {
            lock.unlock();
        }
        return "Appended " + added.length + " bytes to " + given + ".";
    }

    private static boolean endsWithNewline(Path file, long size) throws IOException {
        try (var channel = Files.newByteChannel(file)) {
            channel.position(size - 1);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            channel.read(b);
            return b.get(0) == '\n';
        } catch (NoSuchFileException e) {
            return true;
        }
    }

    private static int integerArg(Map<String, Object> args, String key, int fallback) {
        Object v = args.get(key);
        if (v == null) return fallback;
        try {
            int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v).trim());
            if (n < 1) throw new ToolRefusal(key + " must be 1 or more");
            return n;
        } catch (NumberFormatException e) {
            throw new ToolRefusal(key + " must be a whole number");
        }
    }

    @Override
    public java.util.Map<String, Object> getParametersSchema() {
        return io.github.llm4j.model.ToolSchema.object().enumeration("action", "What to do", true, java.util.List.of("read", "list", "exists", "write", "append")).string("path", "A path relative to the tool's directory", false).string("content", "The text to write or append", false).integer("from_line", "First line to read (read)", false).integer("lines", "How many lines to read (read)", false).string("pattern", "A glob to filter names (list)", false).build();
    }
}
