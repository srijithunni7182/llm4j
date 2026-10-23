package io.github.llm4j.loom.symbols;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Test helper: scripts in a temp folder, indexed, and ranges read back as text. */
final class SymbolsFixture {

    private SymbolsFixture() {}

    static SymbolIndex index(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return new SymbolService().index(file);
    }

    /** The text a range covers, with the same line and column arithmetic an editor uses. */
    static String slice(String text, Range r) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int l = r.line(); l <= r.endLine(); l++) {
            String line = lines[l];
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int from = l == r.line() ? r.col() : 0;
            int to = l == r.endLine() ? r.endCol() : line.length();
            sb.append(line, from, Math.min(to, line.length()));
            if (l < r.endLine()) sb.append('\n');
        }
        return sb.toString();
    }

    static SymbolIndex.Definition def(SymbolIndex i, String key) {
        List<SymbolIndex.Definition> found =
                i.definitions().stream().filter(d -> d.key().equals(key)).toList();
        if (found.isEmpty())
            throw new AssertionError("no definition " + key + " in " + i.definitions());
        return found.get(0);
    }

    static List<SymbolIndex.Reference> refs(SymbolIndex i, String to) {
        return i.references().stream().filter(r -> r.to().equals(to)).toList();
    }

    static SymbolIndex.Binding binding(SymbolIndex i, String key) {
        return i.bindings().stream()
                .filter(b -> b.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no binding " + key + " in " + i.bindings()));
    }

    static final String TRIAGE =
            """
            tool Mailer { use: email  outbox: "outbox"  from: "a@example.com"  to: "b@example.com" }
            intentclassifier Intent {
                model: "claude-opus-5-5"
                choose kind: order_status | refund | other
                ask urgent: "Is the customer threatening to leave?"
                confidence at least 0.8 else escalate
            }
            agent Replier { model: "claude-haiku-5-5"  system: "Answer the customer politely."  tools: [Mailer] }
            agent Checker { model: "claude-haiku-5-5"  output_schema: { ok: boolean, score: number, tags: list<string> } }
            workflow Main(message) {
                intentclassify Intent on message -> intent
                checkpoint start
                alt (intent.kind == "order_status") {
                    delegate "Reply to: {message}" to Replier -> reply
                    delegate "Check {reply}" to Checker -> verdict
                    return reply
                } else {
                    return "escalated"
                }
            }
            """;
}
