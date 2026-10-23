package io.github.llm4j.loom.symbols;

import io.github.llm4j.loom.lexer.Token;
import io.github.llm4j.loom.lexer.TokenType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the names in one file's tokens: what is defined, where each name is used, and which
 * variables a workflow makes. It reads only the token stream (with the places the language's own
 * lexer gave them), following the same phrases the parser does, so a half-typed block still gives
 * what it has. Types and resolution are decided afterwards, by {@link SymbolService}, from the
 * parsed scripts.
 */
final class FileIndexer {

    /** A variable made in a workflow, before its type is known. */
    record RawBinding(
            String name,
            Range range,
            String workflow,
            String role,
            String producedBy,
            Range producedAt,
            String typeOf,
            String typeName,
            int statementLine) {}

    /** A name used, before it is known to be defined. */
    record RawRef(String kind, String name, String role, Range range, boolean weak, String scope) {}

    record RawDef(String kind, String name, Range range, String scope, String detail) {}

    record RawScope(String workflow, List<String> params, Range range) {}

    final List<RawDef> definitions = new ArrayList<>();
    final List<RawRef> references = new ArrayList<>();
    final List<RawBinding> bindings = new ArrayList<>();
    final List<RawScope> scopes = new ArrayList<>();

    /** The files this one imports, as written. */
    final List<String> imports = new ArrayList<>();

    /** The {@code prompts:} folder the script names, or null. */
    String promptsDir;

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)((?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private final List<Token> t;

    FileIndexer(List<Token> tokens) {
        this.t = tokens;
        index();
    }

    // ---- top level

    private void index() {
        int i = 0;
        while (i < t.size() && type(i) != TokenType.EOF) {
            int next = declaration(i);
            i = Math.max(next, i + 1);
        }
    }

    /** Reads the declaration starting at {@code i}; returns the index after it. */
    private int declaration(int i) {
        TokenType type = type(i);
        switch (type) {
            case IMPORT -> {
                if (type(i + 1) == TokenType.STRING_LITERAL) {
                    imports.add(t.get(i + 1).getValue());
                    references.add(
                            new RawRef(
                                    "file",
                                    t.get(i + 1).getValue(),
                                    "import",
                                    stringContent(
                                            t.get(i + 1), 0, t.get(i + 1).getValue().length()),
                                    false,
                                    null));
                }
                return i + 2;
            }
            case AGENT -> {
                return named(i, "agent", true);
            }
            case TOOL -> {
                return named(i, "tool", false);
            }
            case MCP -> {
                return named(i, "mcp", false);
            }
            case KNOWLEDGE -> {
                return named(i, "knowledge", false);
            }
            case ROUTING -> {
                return named(i, "routing", false);
            }
            case PERSONA -> {
                if (isName(i + 1)) return named(i, "persona", false);
                return i + 1;
            }
            case WORKFLOW -> {
                return workflow(i);
            }
            case IDENTIFIER -> {
                String word = value(i);
                if (isName(i + 1) && type(i + 2) == TokenType.LBRACE) {
                    switch (word) {
                        case "provider" -> {
                            return named(i, "provider", false);
                        }
                        case "intentclassifier" -> {
                            return named(i, "classifier", false);
                        }
                        case "decision" -> {
                            return decision(i);
                        }
                        case "supervise" -> {
                            references.add(
                                    new RawRef(
                                            "agent",
                                            value(i + 1),
                                            "supervise",
                                            range(t.get(i + 1)),
                                            false,
                                            null));
                            return blockEnd(i + 2) + 1;
                        }
                        default -> {}
                    }
                }
                if (word.equals("prompts")
                        && type(i + 1) == TokenType.COLON
                        && type(i + 2) == TokenType.STRING_LITERAL) {
                    promptsDir = value(i + 2);
                    return i + 3;
                }
                return i + 1;
            }
            default -> {
                return i + 1;
            }
        }
    }

    /**
     * {@code keyword Name { ... }}: a definition, and (for an agent) what its settings refer to.
     */
    private int named(int i, String kind, boolean agent) {
        if (!isName(i + 1)) return i + 1;
        Token name = t.get(i + 1);
        int open = type(i + 2) == TokenType.LBRACE ? i + 2 : -1;
        definitions.add(new RawDef(kind, name.getValue(), range(name), null, null));
        if (open < 0) return i + 2;
        int end = blockEnd(open);
        if (agent) agentSettings(open, end);
        else if (kind.equals("classifier")) classifierSettings(open, end);
        return end + 1;
    }

    private int decision(int i) {
        Token name = t.get(i + 1);
        definitions.add(new RawDef("decision", name.getValue(), range(name), null, null));
        int open = i + 2;
        int end = blockEnd(open);
        for (int k = open + 1; k < end; k++) {
            if (isWord(k, "proposed")
                    && isWord(k + 1, "by")
                    && type(k + 2) == TokenType.COLON
                    && isName(k + 3)) {
                references.add(
                        new RawRef(
                                "agent",
                                value(k + 3),
                                "proposer",
                                range(t.get(k + 3)),
                                false,
                                null));
            }
        }
        return end + 1;
    }

    private void agentSettings(int open, int end) {
        int depth = 0;
        for (int k = open + 1; k < end; k++) {
            TokenType type = type(k);
            if (type == TokenType.LBRACE) depth++;
            else if (type == TokenType.RBRACE) depth--;
            if (depth != 0 || type(k + 1) != TokenType.COLON) continue;
            switch (type) {
                case TOOLS -> list(k + 2, "tool", "tool-use");
                case MCP_SERVERS -> list(k + 2, "mcp", "mcp-use");
                case KNOWLEDGE -> list(k + 2, "knowledge", "knowledge-use");
                case ROUTING -> {
                    if (isName(k + 2))
                        references.add(
                                new RawRef(
                                        "routing",
                                        value(k + 2),
                                        "routing-use",
                                        range(t.get(k + 2)),
                                        false,
                                        null));
                }
                case PERSONA -> {
                    if (type(k + 2) == TokenType.STRING_LITERAL)
                        references.add(
                                new RawRef(
                                        "persona",
                                        value(k + 2),
                                        "persona-use",
                                        stringContent(t.get(k + 2), 0, value(k + 2).length()),
                                        true,
                                        null));
                }
                case MODEL -> providerPrefix(k + 2);
                case IDENTIFIER -> {
                    if (value(k).equals("approve") && type(k + 2) == TokenType.LBRACKET)
                        list(k + 2, "tool", "approve");
                    else if (value(k).equals("prompt") && type(k + 2) == TokenType.STRING_LITERAL)
                        promptRef(k + 2);
                }
                default -> {}
            }
        }
    }

    private void classifierSettings(int open, int end) {
        for (int k = open + 1; k < end; k++)
            if (isWord(k, "model") && type(k + 1) == TokenType.COLON) providerPrefix(k + 2);
            else if (type(k) == TokenType.MODEL && type(k + 1) == TokenType.COLON)
                providerPrefix(k + 2);
    }

    /** {@code model: "Provider/name"}: the provider, when the script defines one by that name. */
    private void providerPrefix(int at) {
        if (type(at) != TokenType.STRING_LITERAL) return;
        String text = value(at);
        int slash = text.indexOf('/');
        if (slash <= 0 || !NAME.matcher(text.substring(0, slash)).matches()) return;
        references.add(
                new RawRef(
                        "provider",
                        text.substring(0, slash),
                        "model-provider",
                        stringContent(t.get(at), 0, slash),
                        true,
                        null));
    }

    private void promptRef(int at) {
        String id = value(at);
        String base = id.contains("@") ? id.substring(0, id.indexOf('@')) : id;
        if (base.isEmpty()) return;
        references.add(
                new RawRef(
                        "prompt",
                        id,
                        "prompt-use",
                        stringContent(t.get(at), 0, id.length()),
                        false,
                        null));
    }

    /** {@code [A, B, C]} starting at the bracket: each name is a reference. */
    private void list(int bracket, String kind, String role) {
        if (type(bracket) != TokenType.LBRACKET) return;
        for (int k = bracket + 1; k < t.size() && type(k) != TokenType.RBRACKET; k++) {
            if (type(k) == TokenType.RBRACE || type(k) == TokenType.EOF) return;
            if (isName(k))
                references.add(new RawRef(kind, value(k), role, range(t.get(k)), false, null));
        }
    }

    // ---- workflows

    private int workflow(int i) {
        if (!isName(i + 1)) return i + 1;
        Token name = t.get(i + 1);
        String workflow = name.getValue();
        definitions.add(new RawDef("workflow", workflow, range(name), null, null));
        int k = i + 2;
        List<String> params = new ArrayList<>();
        if (type(k) == TokenType.LPAREN) {
            for (k = k + 1; k < t.size() && type(k) != TokenType.RPAREN; k++) {
                if (type(k) == TokenType.LBRACE || type(k) == TokenType.EOF) break;
                if (isName(k)) {
                    params.add(value(k));
                    bindings.add(
                            new RawBinding(
                                    value(k),
                                    range(t.get(k)),
                                    workflow,
                                    "param",
                                    null,
                                    null,
                                    "unknown",
                                    null,
                                    0));
                }
            }
            if (type(k) == TokenType.RPAREN) k++;
        }
        if (type(k) != TokenType.LBRACE) return k;
        int end = blockEnd(k);
        Token last = t.get(Math.min(end, t.size() - 1));
        Range whole =
                new Range(
                        name.getLine() - 1,
                        name.getColumn() - 1,
                        last.getLine() - 1,
                        last.getColumn() - 1 + (type(end) == TokenType.RBRACE ? 1 : width(last)));
        scopes.add(new RawScope(workflow, params, whole));
        body(workflow, k + 1, end);
        return end + 1;
    }

    /** The statements of a workflow, nested blocks included. */
    private void body(String workflow, int from, int to) {
        Set<Integer> claimed = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = from; i < to; i++) {
            TokenType type = type(i);
            if (type == TokenType.DELEGATE) delegate(workflow, i, claimed);
            else if (type == TokenType.BROADCAST) broadcast(workflow, i, claimed);
            else if (type == TokenType.HANDOFF) handoff(i, claimed);
            else if (type == TokenType.CALL && isName(i + 1) && type(i + 2) == TokenType.LPAREN)
                call(workflow, i, claimed);
            else if (type == TokenType.HUMAN_PROMPT) humanPrompt(workflow, i, claimed);
            else if (type == TokenType.IDENTIFIER) identifierStatement(workflow, i, claimed);
        }
        for (RawBinding b : bindings) if (b.workflow().equals(workflow)) names.add(b.name());
        uses(workflow, from, to, claimed, names);
    }

    private void identifierStatement(String workflow, int i, Set<Integer> claimed) {
        String word = value(i);
        switch (word) {
            case "checkpoint" -> {
                if (type(i + 1) == TokenType.IDENTIFIER) {
                    definitions.add(
                            new RawDef(
                                    "checkpoint",
                                    value(i + 1),
                                    range(t.get(i + 1)),
                                    workflow,
                                    null));
                    claimed.add(i + 1);
                }
            }
            case "rewind" -> {
                if (type(i + 1) == TokenType.TO && isName(i + 2)) {
                    references.add(
                            new RawRef(
                                    "checkpoint",
                                    value(i + 2),
                                    "rewind",
                                    range(t.get(i + 2)),
                                    false,
                                    workflow));
                    claimed.add(i + 2);
                }
            }
            case "intentclassify" -> {
                if (type(i + 1) == TokenType.IDENTIFIER) {
                    references.add(
                            new RawRef(
                                    "classifier",
                                    value(i + 1),
                                    "classify",
                                    range(t.get(i + 1)),
                                    false,
                                    null));
                    claimed.add(i + 1);
                    int arrow = arrowAfter(i + 2, 24);
                    if (arrow > 0 && isName(arrow + 1))
                        bind(
                                workflow,
                                arrow + 1,
                                "result",
                                "intentclassify",
                                i,
                                "classifier",
                                value(i + 1),
                                claimed);
                }
            }
            case "decide" -> {
                if (type(i + 1) == TokenType.IDENTIFIER) {
                    references.add(
                            new RawRef(
                                    "decision",
                                    value(i + 1),
                                    "decide",
                                    range(t.get(i + 1)),
                                    false,
                                    null));
                    claimed.add(i + 1);
                    if (type(i + 2) == TokenType.ARROW && isName(i + 3))
                        bind(
                                workflow,
                                i + 3,
                                "result",
                                "decide",
                                i,
                                "decision",
                                value(i + 1),
                                claimed);
                }
            }
            case "run" -> {
                if (isName(i + 1) && type(i + 2) == TokenType.LPAREN) run(workflow, i, claimed);
            }
            case "set" -> {
                if (isName(i + 1) && type(i + 2) == TokenType.ASSIGN)
                    bind(workflow, i + 1, "set", "set", i, "literal", literalType(i + 3), claimed);
            }
            case "for" -> {
                if (isWord(i + 1, "each") && isName(i + 2)) {
                    bind(workflow, i + 2, "loop", "for each", i, "unknown", null, claimed);
                }
            }
            default -> {}
        }
    }

    private void delegate(String workflow, int i, Set<Integer> claimed) {
        int to = -1;
        for (int k = i + 1; k < Math.min(t.size(), i + 8); k++) {
            if (type(k) == TokenType.TO) {
                to = k;
                break;
            }
            if (type(k) == TokenType.LBRACE || type(k) == TokenType.RBRACE) return;
        }
        if (to < 0 || !isName(to + 1)) return;
        String agent = value(to + 1);
        references.add(
                new RawRef("agent", agent, "delegate-target", range(t.get(to + 1)), false, null));
        claimed.add(to + 1);
        if (type(to + 2) == TokenType.ARROW && isName(to + 3))
            bind(workflow, to + 3, "result", "delegate", i, "agent", agent, claimed);
    }

    private void broadcast(String workflow, int i, Set<Integer> claimed) {
        int to = -1;
        for (int k = i + 1; k < Math.min(t.size(), i + 8); k++) {
            if (type(k) == TokenType.TO) {
                to = k;
                break;
            }
            if (type(k) == TokenType.LBRACE || type(k) == TokenType.RBRACE) return;
        }
        if (to < 0 || type(to + 1) != TokenType.LBRACKET) return;
        int k = to + 2;
        for (; k < t.size() && type(k) != TokenType.RBRACKET; k++) {
            if (type(k) == TokenType.RBRACE || type(k) == TokenType.EOF) return;
            if (isName(k)) {
                references.add(
                        new RawRef(
                                "agent",
                                value(k),
                                "broadcast-target",
                                range(t.get(k)),
                                false,
                                null));
                claimed.add(k);
            }
        }
        if (type(k + 1) == TokenType.ARROW && isName(k + 2))
            bind(workflow, k + 2, "result", "broadcast", i, "list", null, claimed);
    }

    private void handoff(int i, Set<Integer> claimed) {
        for (int k = i + 1; k < Math.min(t.size(), i + 8); k++) {
            if (type(k) == TokenType.TO) {
                if (isName(k + 1)) {
                    references.add(
                            new RawRef(
                                    "agent",
                                    value(k + 1),
                                    "handoff-target",
                                    range(t.get(k + 1)),
                                    false,
                                    null));
                    claimed.add(k + 1);
                }
                return;
            }
            if (type(k) == TokenType.LBRACE || type(k) == TokenType.RBRACE) return;
        }
    }

    private void call(String workflow, int i, Set<Integer> claimed) {
        references.add(
                new RawRef("workflow", value(i + 1), "call", range(t.get(i + 1)), false, null));
        claimed.add(i + 1);
        int close = i + 3;
        while (close < t.size() && type(close) != TokenType.RPAREN) {
            if (type(close) == TokenType.RBRACE || type(close) == TokenType.EOF) return;
            // the names before '=' are the callee's parameters
            if (type(close + 1) == TokenType.ASSIGN) claimed.add(close);
            close++;
        }
        if (type(close + 1) == TokenType.ARROW && isName(close + 2))
            bind(workflow, close + 2, "result", "call", i, "unknown", null, claimed);
    }

    private void run(String workflow, int i, Set<Integer> claimed) {
        references.add(new RawRef("task", value(i + 1), "run", range(t.get(i + 1)), false, null));
        claimed.add(i + 1);
        int close = i + 3;
        while (close < t.size() && type(close) != TokenType.RPAREN) {
            if (type(close) == TokenType.RBRACE || type(close) == TokenType.EOF) return;
            if (type(close + 1) == TokenType.ASSIGN) claimed.add(close);
            close++;
        }
        int arrow = arrowAfter(close + 1, 3);
        if (arrow > 0 && isName(arrow + 1))
            bind(workflow, arrow + 1, "result", "run", i, "unknown", null, claimed);
    }

    private void humanPrompt(String workflow, int i, Set<Integer> claimed) {
        int arrow = arrowAfter(i + 1, 3);
        if (arrow > 0 && isName(arrow + 1))
            bind(workflow, arrow + 1, "result", "human_prompt", i, "string", null, claimed);
    }

    private void bind(
            String workflow,
            int nameAt,
            String role,
            String producedBy,
            int statementAt,
            String typeOf,
            String typeName,
            Set<Integer> claimed) {
        claimed.add(nameAt);
        bindings.add(
                new RawBinding(
                        value(nameAt),
                        range(t.get(nameAt)),
                        workflow,
                        role,
                        producedBy,
                        range(t.get(statementAt)),
                        typeOf,
                        typeName,
                        t.get(statementAt).getLine()));
    }

    /** The type a {@code set} value has, from its first token. */
    private String literalType(int at) {
        return switch (type(at)) {
            case NUMBER_LITERAL -> "number";
            case STRING_LITERAL -> "string";
            default -> "unknown";
        };
    }

    private int arrowAfter(int from, int within) {
        for (int k = from; k < Math.min(t.size(), from + within); k++) {
            if (type(k) == TokenType.ARROW) return k;
            if (type(k) == TokenType.LBRACE || type(k) == TokenType.RBRACE) return -1;
        }
        return -1;
    }

    // ---- uses of variables

    private void uses(String workflow, int from, int to, Set<Integer> claimed, Set<String> names) {
        if (names.isEmpty()) return;
        for (int i = from; i < to; i++) {
            Token token = t.get(i);
            if (type(i) == TokenType.IDENTIFIER && !claimed.contains(i)) {
                String v = token.getValue();
                int dot = v.indexOf('.');
                String root = dot < 0 ? v : v.substring(0, dot);
                if (!names.contains(root)) continue;
                if (type(i + 1) == TokenType.ASSIGN || type(i + 1) == TokenType.COLON) continue;
                references.add(
                        new RawRef(
                                "binding",
                                root,
                                "use",
                                new Range(
                                        token.getLine() - 1,
                                        token.getColumn() - 1,
                                        token.getLine() - 1,
                                        token.getColumn() - 1 + root.length()),
                                false,
                                workflow));
            } else if (type(i) == TokenType.STRING_LITERAL) {
                Matcher m = PLACEHOLDER.matcher(token.getValue());
                while (m.find()) {
                    String root = m.group(1);
                    if (!names.contains(root)) continue;
                    references.add(
                            new RawRef(
                                    "binding",
                                    root,
                                    "use",
                                    stringContent(token, m.start(1), m.end(1)),
                                    false,
                                    workflow));
                }
            }
        }
    }

    // ---- tokens

    private TokenType type(int i) {
        return i >= 0 && i < t.size() ? t.get(i).getType() : TokenType.EOF;
    }

    private String value(int i) {
        return i >= 0 && i < t.size() ? t.get(i).getValue() : "";
    }

    private boolean isWord(int i, String word) {
        return type(i) == TokenType.IDENTIFIER && value(i).equals(word);
    }

    private boolean isName(int i) {
        if (i < 0 || i >= t.size()) return false;
        TokenType type = type(i);
        if (type == TokenType.STRING_LITERAL || type == TokenType.NUMBER_LITERAL) return false;
        return NAME.matcher(value(i)).matches();
    }

    /** The index of the brace that closes the one at {@code open} (or the last token). */
    private int blockEnd(int open) {
        int depth = 0;
        for (int k = open; k < t.size(); k++) {
            if (type(k) == TokenType.LBRACE) depth++;
            if (type(k) == TokenType.RBRACE && --depth == 0) return k;
        }
        return Math.max(open, t.size() - 1);
    }

    private static Range range(Token token) {
        return new Range(
                token.getLine() - 1,
                token.getColumn() - 1,
                token.getLine() - 1,
                token.getColumn() - 1 + width(token));
    }

    private static int width(Token token) {
        return switch (token.getType()) {
            case STRING_LITERAL -> token.getValue().length() + 2;
            case LBRACE,
                    RBRACE,
                    LPAREN,
                    RPAREN,
                    LBRACKET,
                    RBRACKET,
                    COMMA,
                    COLON,
                    ASSIGN,
                    LT,
                    GT,
                    PLUS,
                    PERCENT,
                    PIPE -> 1;
            case EQUALS, NOT_EQUALS, LTE, GTE, ARROW -> 2;
            default -> token.getValue().length();
        };
    }

    /**
     * The range of {@code [from, to)} inside the value of a string token, counting the lines a
     * string can span; the quote before the value is one column.
     */
    static Range stringContent(Token token, int from, int to) {
        String v = token.getValue();
        int line = token.getLine() - 1;
        int col = token.getColumn(); // after the opening quote, 0-based
        int startLine = line;
        int startCol = col;
        int endLine = line;
        int endCol = col;
        for (int k = 0; k < to; k++) {
            if (k == from) {
                startLine = line;
                startCol = col;
            }
            if (v.charAt(k) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        if (from >= to) {
            startLine = line;
            startCol = col;
        }
        endLine = line;
        endCol = col;
        return new Range(startLine, startCol, endLine, endCol);
    }
}
