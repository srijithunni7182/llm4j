package io.github.llm4j.loom.graph;

import java.util.List;

/** A file in the import closure and the files it imports directly, as absolute normalised paths. */
public record ImportFile(String path, List<String> imports) {

    public ImportFile {
        imports = List.copyOf(imports);
    }
}
