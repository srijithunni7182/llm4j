package io.github.llm4j.loom.generate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Model replies for the fixture project: the files, whole or broken in one way. */
final class ProjectReplies {

    private ProjectReplies() {}

    static String files(Map<String, String> files) {
        return "Here are the files.\n" + FileBlocks.render(files);
    }

    static String good() {
        return files(modelFiles());
    }

    /** What the model writes: the fixture without the files the generator writes itself. */
    static Map<String, String> modelFiles() {
        Map<String, String> f = new LinkedHashMap<>(GenerateFixtures.files());
        f.put(
                "tests/main.test.yaml",
                "script: main.loom\nworkflow: Main\ncases:\n  - name: stub\n    input: { ticket_body: \"t\" }\n");
        return f;
    }

    static String withScript(String script) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("main.loom", script);
        return files(f);
    }
}
