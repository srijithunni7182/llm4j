package io.github.llm4j.loom.review.pack;

import java.util.List;
import java.util.Optional;

/** Where a pack's files are: inside the jar or in a folder. A pack can read only its own files. */
public interface PackFiles {

    /** The text of a file by path relative to the pack, or empty when there is none. */
    Optional<String> read(String relativePath);

    /** Every rule file under {@code rules/}, as relative paths, in a stable order. */
    List<String> ruleFiles();

    /** Every file of the pack (rules, examples, manifest) by relative path, in a stable order. */
    List<String> allFiles();

    /** Where the pack is, for messages. */
    String where();
}
