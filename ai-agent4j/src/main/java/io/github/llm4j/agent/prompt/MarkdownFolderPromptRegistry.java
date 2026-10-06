package io.github.llm4j.agent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * A {@link PromptRegistry} that reads a folder of markdown files, one file per prompt version:
 *
 * <pre>
 * prompts/
 *   researcher/v1.md
 *   researcher/v2.md
 *   writer.md          (an id with one version; this is version v1)
 * </pre>
 *
 * <p>A file may start with a YAML front matter block ({@code description}, {@code variables}); the rest is the prompt
 * text, verbatim, with leading and trailing blank lines trimmed. The latest version of an id is its highest version
 * number. An id is lower-case letters, digits, {@code -} and {@code _}; a version is {@code v} and a whole number.
 *
 * <p>Nothing outside the folder can be read: an id or version that is not of that shape finds nothing, a symbolic link
 * that leaves the folder is refused, and a file over {@value #MAX_BYTES} bytes or not valid UTF-8 is refused and
 * reported by {@link #problems()}. Anything else found in the folder is ignored and reported there too.
 */
public final class MarkdownFolderPromptRegistry implements PromptRegistry, AutoCloseable {

    /** The most a prompt file may hold. */
    public static final int MAX_BYTES = 64 * 1024;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern VERSION = Pattern.compile("v([0-9]+)");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);

    /** A prompt version and where it came from. */
    public record Entry(String id, String version, String text, String description, List<String> variables, Path file) {}

    /** Something in the folder that was ignored or refused. */
    public record Problem(Path file, String message) {}

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path root;
    private volatile Map<String, TreeMap<Integer, Entry>> prompts = Map.of();
    private volatile List<Problem> problems = List.of();
    private WatchService watchService;
    private ExecutorService watcher;

    /** Reads the folder now. A folder that does not exist is an empty registry ({@link #exists()} says so). */
    public MarkdownFolderPromptRegistry(Path root) {
        this.root = root.toAbsolutePath().normalize();
        reload();
    }

    /** Reads the folder and reloads it whenever a file in it changes, until {@link #close()}. */
    public static MarkdownFolderPromptRegistry watching(Path root) {
        MarkdownFolderPromptRegistry registry = new MarkdownFolderPromptRegistry(root);
        registry.startWatching();
        return registry;
    }

    public Path root() {
        return root;
    }

    public boolean exists() {
        return Files.isDirectory(root);
    }

    /** The ids that have at least one version, sorted. */
    public List<String> ids() {
        return List.copyOf(new java.util.TreeSet<>(prompts.keySet()));
    }

    /** The versions of an id, oldest first ({@code v1, v2, …}); empty for an unknown id. */
    public List<String> versions(String id) {
        TreeMap<Integer, Entry> versions = prompts.get(id);
        if (versions == null) return List.of();
        List<String> out = new ArrayList<>();
        versions.values().forEach(e -> out.add(e.version()));
        return out;
    }

    /** The entry for an id and version, with its description, variables and file. */
    public Optional<Entry> entry(String id, String version) {
        if (id == null || version == null) return Optional.empty();
        TreeMap<Integer, Entry> versions = prompts.get(id);
        var m = VERSION.matcher(version);
        if (versions == null || !m.matches()) return Optional.empty();
        return Optional.ofNullable(versions.get(Integer.parseInt(m.group(1))));
    }

    /** The newest version of an id. */
    public Optional<Entry> latest(String id) {
        TreeMap<Integer, Entry> versions = id == null ? null : prompts.get(id);
        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.lastEntry().getValue());
    }

    /** What the last read ignored or refused. */
    public List<Problem> problems() {
        return problems;
    }

    @Override
    public Optional<PromptTemplate> get(String id) {
        return latest(id).map(MarkdownFolderPromptRegistry::template);
    }

    @Override
    public Optional<PromptTemplate> get(String id, String version) {
        return entry(id, version).map(MarkdownFolderPromptRegistry::template);
    }

    private static PromptTemplate template(Entry e) {
        return new PromptTemplate(e.id(), e.version(), e.text());
    }

    @Override
    public synchronized void reload() {
        Map<String, TreeMap<Integer, Entry>> found = new TreeMap<>();
        List<Problem> issues = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (DirectoryStream<Path> top = Files.newDirectoryStream(root)) {
                List<Path> entries = new ArrayList<>();
                top.forEach(entries::add);
                entries.sort(Comparator.comparing(Path::toString));
                for (Path p : entries) readTopLevel(p, found, issues);
            } catch (IOException e) {
                issues.add(new Problem(root, "cannot read the prompt folder: " + e.getMessage()));
            }
        }
        prompts = found;
        problems = Collections.unmodifiableList(issues);
    }

    private void readTopLevel(Path p, Map<String, TreeMap<Integer, Entry>> found, List<Problem> issues) {
        String name = p.getFileName().toString();
        if (Files.isDirectory(p)) {
            if (!ID.matcher(name).matches()) {
                issues.add(new Problem(p, "ignored: '" + name + "' is not a prompt id (lower-case letters, digits, - and _)"));
                return;
            }
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(p)) {
                List<Path> files = new ArrayList<>();
                versions.forEach(files::add);
                files.sort(Comparator.comparing(Path::toString));
                for (Path f : files) {
                    String file = f.getFileName().toString();
                    var m = VERSION.matcher(file.endsWith(".md") ? file.substring(0, file.length() - 3) : "");
                    if (!m.matches()) {
                        issues.add(new Problem(f, "ignored: '" + file + "' is not a version file such as v1.md"));
                        continue;
                    }
                    add(found, issues, name, "v" + Integer.parseInt(m.group(1)), Integer.parseInt(m.group(1)), f);
                }
            } catch (IOException e) {
                issues.add(new Problem(p, "cannot read: " + e.getMessage()));
            }
        } else if (name.endsWith(".md")) {
            String id = name.substring(0, name.length() - 3);
            if (!ID.matcher(id).matches()) {
                issues.add(new Problem(p, "ignored: '" + id + "' is not a prompt id (lower-case letters, digits, - and _)"));
                return;
            }
            add(found, issues, id, "v1", 1, p);
        } else {
            issues.add(new Problem(p, "ignored: not a prompt file or folder"));
        }
    }

    private void add(Map<String, TreeMap<Integer, Entry>> found, List<Problem> issues, String id, String version, int number, Path file) {
        try {
            Path real = file.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                issues.add(new Problem(file, "refused: it is a link that leaves the prompt folder"));
                return;
            }
            long size = Files.size(real);
            if (size > MAX_BYTES) {
                issues.add(new Problem(file, "refused: " + size + " bytes is over the " + MAX_BYTES + " byte limit"));
                return;
            }
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(Files.readAllBytes(real))).toString();
            } catch (CharacterCodingException e) {
                issues.add(new Problem(file, "refused: not valid UTF-8"));
                return;
            }
            String description = null;
            List<String> variables = List.of();
            var fm = FRONT_MATTER.matcher(content);
            if (fm.find()) {
                try {
                    Map<?, ?> meta = YAML.readValue(fm.group(1), Map.class);
                    if (meta != null) {
                        if (meta.get("description") != null) description = String.valueOf(meta.get("description"));
                        if (meta.get("variables") instanceof List<?> list) {
                            variables = list.stream().map(String::valueOf).toList();
                        }
                    }
                } catch (IOException e) {
                    issues.add(new Problem(file, "refused: the front matter is not valid YAML (" + firstLine(e.getMessage()) + ")"));
                    return;
                }
                content = content.substring(fm.end());
            }
            String text = content.strip();
            found.computeIfAbsent(id, k -> new TreeMap<>()).put(number, new Entry(id, version, text, description, variables, file));
        } catch (IOException e) {
            issues.add(new Problem(file, "cannot read: " + e.getMessage()));
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    private void startWatching() {
        if (!Files.isDirectory(root)) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            register(root);
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
                for (Path d : dirs) register(d);
            }
            watcher = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MarkdownFolderPromptRegistry-Watcher");
                t.setDaemon(true);
                return t;
            });
            watcher.submit(this::watchLoop);
        } catch (IOException e) {
            problems = List.of(new Problem(root, "not watching for changes: " + e.getMessage()));
        }
    }

    private void register(Path dir) throws IOException {
        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                key.pollEvents();
                Thread.sleep(100);
                reload();
                if (!key.reset()) break;
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                break;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (watcher != null) watcher.shutdownNow();
        if (watchService != null) watchService.close();
    }
}
