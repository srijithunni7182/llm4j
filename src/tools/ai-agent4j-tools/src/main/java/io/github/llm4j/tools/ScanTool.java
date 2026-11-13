package io.github.llm4j.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** {@code scan}: text, a file or a directory under the root, against {@link ScanRules}. */
final class ScanTool extends GenericTool {

    private static final ObjectMapper OUT = new ObjectMapper();

    private final RootedFiles files;
    private final Path root;
    private final boolean secrets;
    private final Set<String> pii;
    private final int maxFiles;
    private final int maxFindings;

    ScanTool(
            String name,
            RootedFiles files,
            Path root,
            boolean secrets,
            Set<String> pii,
            int maxFiles,
            int maxFindings,
            EffectContext context) {
        super(
                name,
                "scan",
                "Looks for credentials and personal data and says where, never what: text: the text"
                        + (files.enabled() ? ", or path: a file or directory" : "")
                        + ". A clean result means nothing the rules know was found.",
                new Redactor(List.of()),
                context);
        this.files = files;
        this.root = root;
        this.secrets = secrets;
        this.pii = pii;
        this.maxFiles = maxFiles;
        this.maxFindings = maxFindings;
    }

    /** What has been found so far, kept to the cap. */
    private final class Findings {
        final List<Map<String, Object>> kept = new ArrayList<>();
        int count;
        int filesScanned;
        int skipped;
        boolean truncated;

        void scan(String text, String file) {
            for (ScanRules.Hit h : ScanRules.scan(text, secrets, pii)) {
                count++;
                if (kept.size() >= maxFindings) {
                    truncated = true;
                    continue;
                }
                kept.add(place(text, h, file));
            }
        }
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws Exception {
        Findings f = new Findings();
        String path = optionalText(args, "path");
        if (path != null) scanPath(path, f);
        else if (args.get("text") instanceof String text) f.scan(files.bounded("text", text), null);
        else
            throw new ToolRefusal(
                    "text is required"
                            + (files.enabled() ? " (or path: a file or directory)" : ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules_version", ScanRules.VERSION);
        out.put("count", f.count);
        out.put("findings", f.kept);
        out.put("truncated", f.truncated);
        if (path != null) {
            out.put("files", f.filesScanned);
            out.put("skipped", f.skipped);
        }
        out.put(
                "note",
                f.count == 0
                        ? "nothing the rules know was found; the rules are a net, not a proof"
                        : "each finding is a rule and a place; the matched text is never shown");
        return OUT.writeValueAsString(out);
    }

    private void scanPath(String path, Findings f) throws IOException {
        if (!files.enabled()) files.read(path); // refuses, naming root:
        Path start;
        try {
            start = SafePaths.inside(root, path);
        } catch (IllegalArgumentException e) {
            throw new ToolRefusal(path + " is outside the tool's root");
        }
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            scanFile(path, files.read(path), f);
            return;
        }
        List<Path> found;
        try (Stream<Path> all = Files.walk(start)) {
            found =
                    all.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList();
        }
        for (Path p : found) {
            if (f.filesScanned >= maxFiles) {
                f.truncated = true;
                break;
            }
            String relative = root.relativize(p).toString().replace('\\', '/');
            String text;
            try {
                text = files.read(relative);
            } catch (ToolRefusal tooBigOrGone) {
                f.skipped++;
                continue;
            }
            scanFile(relative, text, f);
        }
    }

    private static void scanFile(String name, String text, Findings f) {
        if (text.indexOf('\0') >= 0) {
            f.skipped++; // not text
            return;
        }
        f.filesScanned++;
        f.scan(text, name);
    }

    /** A hit as a rule, the file, the line and the columns: nothing of the text itself. */
    private static Map<String, Object> place(String text, ScanRules.Hit h, String file) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < h.start(); i++)
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", h.rule());
        if (file != null) out.put("file", file);
        out.put("line", line);
        out.put("start", h.start() - lineStart + 1);
        out.put("end", h.end() - lineStart + 1);
        return out;
    }
}
