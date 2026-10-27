package io.github.llm4j.loom.bind;

import io.github.llm4j.loom.ast.IntentClassifierDef;
import io.github.llm4j.loom.ast.IntentClassifyStmt;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ReturnStmt;
import io.github.llm4j.loom.ast.StatementWalker;
import io.github.llm4j.loom.ast.ValueExpr;
import io.github.llm4j.loom.ast.WorkflowDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a workflow hands back, as far as the script says: the fields of its {@code return { ... }}
 * and a Java type for each that is knowable (a quoted text is text, a number a number, a choice of
 * an {@code intentclassify} an enum). Anything else is text, and the generated code says so; a type
 * is never guessed. Several returns with different shapes give the union of their fields, each
 * field absent on some path being nullable.
 */
public final class SchemaInfer {

    private SchemaInfer() {}

    /** A Java type: text, a number, a boolean, or an enum made from the labels of a question. */
    public record Type(Kind kind, String enumName, List<String> labels) {
        public enum Kind {
            TEXT,
            NUMBER,
            BOOL,
            ENUM
        }

        static Type text() {
            return new Type(Kind.TEXT, null, List.of());
        }
    }

    /**
     * @param nullable true when some return does not set it
     * @param paths how many returns set it, of {@code returns}
     */
    public record Field(String name, Type type, boolean nullable, int paths) {}

    /**
     * @param fields empty when the workflow returns no object
     * @param single the type of a lone non-object return (a text, a reference), else null
     * @param returns how many {@code return} statements the workflow has
     */
    public record Shape(List<Field> fields, Type single, int returns) {
        public boolean hasObject() {
            return !fields.isEmpty();
        }
    }

    public static Shape infer(LoomScript script, WorkflowDef workflow) {
        Map<String, String> classifierOf = new LinkedHashMap<>();
        List<ReturnStmt> returns = new ArrayList<>();
        StatementWalker.walk(
                workflow.getStatements(),
                s -> {
                    if (s instanceof IntentClassifyStmt c)
                        classifierOf.put(c.getVariableName(), c.getClassifier());
                    if (s instanceof ReturnStmt r) returns.add(r);
                });
        Map<String, Type> types = new LinkedHashMap<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        Type single = null;
        for (ReturnStmt r : returns) {
            ValueExpr v = r.getValue();
            if (v instanceof ValueExpr.Obj o) {
                o.fields()
                        .forEach(
                                (name, value) -> {
                                    Type t = typeOf(script, classifierOf, value);
                                    types.merge(name, t, (a, b) -> a.equals(b) ? a : Type.text());
                                    seen.merge(name, 1, Integer::sum);
                                });
            } else if (v != null) single = typeOf(script, classifierOf, v);
        }
        List<Field> fields = new ArrayList<>();
        types.forEach(
                (name, t) ->
                        fields.add(
                                new Field(
                                        name, t, seen.get(name) < returns.size(), seen.get(name))));
        return new Shape(fields, fields.isEmpty() ? single : null, returns.size());
    }

    private static Type typeOf(LoomScript script, Map<String, String> classifierOf, ValueExpr v) {
        if (v instanceof ValueExpr.Num) return new Type(Type.Kind.NUMBER, null, List.of());
        if (v instanceof ValueExpr.Bool) return new Type(Type.Kind.BOOL, null, List.of());
        if (v instanceof ValueExpr.Ref ref) {
            String[] parts = ref.path().split("\\.", 2);
            String classifier = classifierOf.get(parts[0]);
            if (classifier != null && parts.length == 2) {
                for (IntentClassifierDef def : script.getIntentClassifiers()) {
                    if (!def.getName().equals(classifier)) continue;
                    IntentClassifierDef.Question q = def.question(parts[1]);
                    if (q == null) break;
                    List<String> labels =
                            q.kind() == IntentClassifierDef.Kind.ASK
                                    ? List.of("yes", "no")
                                    : def.possibleLabels(q);
                    return new Type(
                            Type.Kind.ENUM,
                            Naming.type(def.getName()) + Naming.type(q.name()),
                            labels);
                }
            }
        }
        return Type.text();
    }
}
