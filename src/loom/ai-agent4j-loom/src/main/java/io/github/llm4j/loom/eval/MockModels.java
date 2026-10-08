package io.github.llm4j.loom.eval;

import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.LLMClientFactory;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A model that costs nothing: every answer is a fixed, well-formed reply, so a script can be run end to end to check its wiring (steps,
 * prompts, branches, schemas) and not its quality. When a step asks for JSON in a schema ({@code expecting}, {@code output_schema}), the reply
 * is a value of that schema (the first choice of an enum, {@code "mock"} for text, 0 for a number), so the next step has something to read.
 */
public final class MockModels implements LLMClientFactory {

    /** What a mock answers when no schema is asked for. */
    public static final String ANSWER = "[mock answer]";

    private static final String MARKER = "following this schema: ";

    @Override
    public LLMClient createClient(String model) {
        return new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest request) {
                String text = lastUserText(request);
                int at = text.lastIndexOf(MARKER);
                String answer = at < 0 ? ANSWER : sample(new Parser(text.substring(at + MARKER.length())).value());
                String reply = "```json\n{\"thought\": \"mock\", \"final_answer\": " + quote(answer) + "}\n```";
                return LLMResponse.builder().content(reply).model(model).tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest request) {
                return Stream.of(chat(request));
            }
        };
    }

    @Override
    public String problem(String model) {
        return null;
    }

    private static String lastUserText(LLMRequest request) {
        var messages = request.getMessages();
        StringBuilder all = new StringBuilder();
        for (var m : messages) if (m.getContent() != null) all.append(m.getContent()).append('\n');
        return all.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // ── the schema text the harness writes, read back and sampled ───────────────────────────

    /** A schema as the harness prints it: {@code {a: string, b: enum[X, Y], c: list<number>}}, {@code string}, {@code number}, {@code boolean}. */
    sealed interface Shape permits Obj, Choice, Seq, Plain {}

    record Obj(java.util.LinkedHashMap<String, Shape> fields) implements Shape {}

    record Choice(String first) implements Shape {}

    record Seq(Shape of) implements Shape {}

    record Plain(String type) implements Shape {}

    static final class Parser {
        private final String s;
        private int i;

        Parser(String text) {
            this.s = text;
        }

        Shape value() {
            skip();
            if (peek("{")) {
                i++;
                var fields = new java.util.LinkedHashMap<String, Shape>();
                skip();
                while (i < s.length() && s.charAt(i) != '}') {
                    int colon = s.indexOf(':', i);
                    if (colon < 0) break;
                    String name = s.substring(i, colon).strip();
                    i = colon + 1;
                    fields.put(name, value());
                    skip();
                    if (i < s.length() && s.charAt(i) == ',') i++;
                    skip();
                }
                if (i < s.length()) i++;
                return new Obj(fields);
            }
            if (peek("enum[")) {
                int close = s.indexOf(']', i);
                String inner = s.substring(i + 5, close < 0 ? s.length() : close);
                i = close < 0 ? s.length() : close + 1;
                String first = inner.split(",")[0].strip();
                return new Choice(first);
            }
            if (peek("list<")) {
                i += 5;
                Shape of = value();
                skip();
                if (i < s.length() && s.charAt(i) == '>') i++;
                return new Seq(of);
            }
            Matcher m = Pattern.compile("[a-z]+").matcher(s);
            if (m.find(i) && m.start() == i) {
                i = m.end();
                return new Plain(m.group());
            }
            return new Plain("string");
        }

        private boolean peek(String prefix) {
            return s.startsWith(prefix, i);
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    /** A JSON text of a value of the shape. */
    static String sample(Shape shape) {
        if (shape instanceof Obj o) {
            List<String> parts = new ArrayList<>();
            o.fields().forEach((k, v) -> parts.add(quote(k) + ": " + sample(v)));
            return "{" + String.join(", ", parts) + "}";
        }
        if (shape instanceof Choice c) return quote(c.first());
        if (shape instanceof Seq) return "[]";
        String type = ((Plain) shape).type();
        return switch (type) {
            case "number", "integer", "int", "double", "float" -> "0";
            case "boolean", "bool" -> "false";
            default -> quote("mock");
        };
    }
}
