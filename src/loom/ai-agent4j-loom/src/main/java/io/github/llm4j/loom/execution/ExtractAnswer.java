package io.github.llm4j.loom.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.ExtractorDef;
import io.github.llm4j.loom.ast.SchemaDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an extractor's reply (docs/specs/24-stateless-nodes, R4). Three outcomes, and the runtime
 * never invents a value:
 *
 * <ul>
 *   <li>fields: a map holding every field the text stated; an optional field the model left out or
 *       answered {@code null} is absent from the map, which conditions read as {@code missing};
 *   <li>{@link StatedMissing}: a <em>required</em> field answered {@code null} is the model saying
 *       the text does not state it. Asking again would repeat that answer, so it is not retried;
 *   <li>{@link StructuredAnswer.SchemaViolation}: anything else wrong (not JSON, a wrong kind, an
 *       unknown enum word, a required field left out): a failed attempt, corrected and retried.
 * </ul>
 */
final class ExtractAnswer {

    /** Required fields the model answered {@code null}: the text does not state them. */
    static final class StatedMissing extends RuntimeException {
        private final List<String> fields;

        StatedMissing(List<String> fields) {
            super(
                    "the text does not state "
                            + String.join(", ", fields)
                            + ", and "
                            + (fields.size() == 1 ? "it is" : "they are")
                            + " required");
            this.fields = List.copyOf(fields);
        }

        /** The names of the required fields that were not found. */
        List<String> fields() {
            return fields;
        }
    }

    /** The fields found, in the order the extractor declares them, and the optional ones absent. */
    record Fields(Map<String, Object> values, List<String> missing) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractAnswer() {}

    static Fields read(String raw, ExtractorDef def) {
        SchemaDef schema = def.getFields();
        Object parsed;
        try {
            parsed = JSON.readValue(StructuredAnswer.extract(raw, schema), Object.class);
        } catch (StructuredAnswer.SchemaViolation violation) {
            throw violation;
        } catch (Exception e) {
            throw new StructuredAnswer.SchemaViolation(
                    List.of("the answer is not valid JSON (" + firstLine(e.getMessage()) + ")"));
        }
        if (!(parsed instanceof Map<?, ?> given)) {
            throw new StructuredAnswer.SchemaViolation(
                    List.of("the answer is not a JSON object with the fields"));
        }
        List<String> problems = new ArrayList<>();
        List<String> notStated = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, SchemaDef> field : schema.getFields().entrySet()) {
            String name = field.getKey();
            boolean optional = def.isOptional(name);
            if (!given.containsKey(name)) {
                if (optional) absent.add(name);
                else problems.add(name + ": left out (answer null when the text does not state it)");
            } else if (given.get(name) == null) {
                if (optional) absent.add(name);
                else notStated.add(name);
            } else {
                List<String> wrong = StructuredAnswer.problems(given.get(name), field.getValue(), name);
                if (wrong.isEmpty()) values.put(name, given.get(name));
                else problems.addAll(wrong);
            }
        }
        if (!problems.isEmpty()) throw new StructuredAnswer.SchemaViolation(problems);
        if (!notStated.isEmpty()) throw new StatedMissing(notStated);
        return new Fields(values, absent);
    }

    private static String firstLine(String message) {
        if (message == null) return "unreadable";
        int cut = message.indexOf('\n');
        return cut < 0 ? message : message.substring(0, cut);
    }
}
