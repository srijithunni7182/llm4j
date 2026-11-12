package io.github.llm4j.loom.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a workflow may report about itself: {@code workflow W(params) progress { stage: string, done:
 * number }} (phase 34b, D6). One flat level of {@code string}, {@code number}, {@code boolean} and
 * {@code enum[...]} fields, in the order written. A parent reads the latest report through the
 * child's handle; it is data, never control.
 *
 * @param fields each field's name and its type, as a schema of one scalar
 * @param line the line of the word {@code progress}
 */
public record ProgressDecl(Map<String, SchemaDef> fields, int line) {
    public ProgressDecl {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
