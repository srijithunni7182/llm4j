package io.github.llm4j.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.agent.tool.EffectContext;
import java.util.List;
import java.util.Map;

/** {@code verify}: a JUnit report or a schema check, as one {@link Verdict}. */
final class VerifyTool extends GenericTool {

    private static final ObjectMapper OUT = new ObjectMapper();
    private static final ObjectMapper IN =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static {
        IN.getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(SchemaCheck.MAX_DEPTH)
                                .build());
    }

    private final RootedFiles files;
    private final int maxItems;

    VerifyTool(String name, RootedFiles files, int maxItems, EffectContext context) {
        super(
                name,
                "verify",
                "Turns a check's result into counts you can rely on. format: junit (report: the XML,"
                        + (files.enabled() ? " or source: a file" : "")
                        + ") or schema (schema_text"
                        + (files.enabled() ? " or schema: a file" : "")
                        + ", and document"
                        + (files.enabled() ? " or source: a file" : "")
                        + "). Returns status (passed, failed or empty), total, passed, failed,"
                        + " errors, skipped and items.",
                new Redactor(List.of()),
                context);
        this.files = files;
        this.maxItems = maxItems;
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) throws Exception {
        String format = optionalText(args, "format");
        Verdict verdict;
        if ("junit".equals(format)) verdict = junit(args);
        else if ("schema".equals(format)) verdict = schema(args);
        else throw new ToolRefusal("format must be junit or schema, not " + format);
        return OUT.writeValueAsString(verdict.toMap());
    }

    private Verdict junit(Map<String, Object> args) {
        String source = optionalText(args, "source");
        String report = textOrFile(args, "report", source);
        return JUnitReport.read(report, source, maxItems);
    }

    private Verdict schema(Map<String, Object> args) {
        String source = optionalText(args, "source");
        String schemaFile = optionalText(args, "schema");
        String schemaText =
                schemaFile != null
                        ? files.read(schemaFile)
                        : args.get("schema_text") instanceof String s
                                ? files.bounded("schema_text", s)
                                : null;
        if (schemaText == null)
            throw new ToolRefusal(
                    "a schema check needs schema_text"
                            + (files.enabled() ? " or schema (a file)" : ""));
        String document = textOrFile(args, "document", source);
        JsonNode schema;
        try {
            schema = IN.readTree(schemaText);
        } catch (StreamConstraintsException e) {
            return Verdict.unreadable(
                    "schema",
                    source,
                    maxItems,
                    "the schema is nested more than " + SchemaCheck.MAX_DEPTH + " deep");
        } catch (JsonProcessingException e) {
            return Verdict.unreadable("schema", source, maxItems, "the schema is not JSON");
        }
        String problem = SchemaCheck.problem(schema);
        if (problem != null) return Verdict.unreadable("schema", source, maxItems, problem);
        JsonNode doc;
        try {
            doc = IN.readTree(document);
        } catch (StreamConstraintsException e) {
            return Verdict.unreadable(
                    "schema",
                    source,
                    maxItems,
                    "the document is nested more than " + SchemaCheck.MAX_DEPTH + " deep");
        } catch (JsonProcessingException e) {
            return Verdict.unreadable("schema", source, maxItems, "the document is not JSON");
        }
        if (doc == null || doc.isMissingNode())
            return Verdict.unreadable("schema", source, maxItems, "the document is empty");
        Verdict v = new Verdict("schema", source, maxItems);
        SchemaCheck.check(schema, doc, "", v);
        v.checkedOne();
        return v;
    }

    /** The text given under {@code key}, or the file {@code source} names. */
    private String textOrFile(Map<String, Object> args, String key, String source) {
        if (source != null) return files.read(source);
        Object v = args.get(key);
        if (v instanceof String s) return files.bounded(key, s);
        throw new ToolRefusal(
                key + " is required" + (files.enabled() ? " (or source: a file)" : ""));
    }
}
