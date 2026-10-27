package io.github.llm4j.loom.review.pack;

import java.util.List;

/**
 * What a rule pack says about itself ({@code pack.yaml}).
 *
 * @param name the pack's name: lower case letters, digits and hyphens
 * @param version {@code major.minor.patch}
 * @param factModel the fact model version it was written for
 * @param rulesSchema the version of the rule document format
 * @param prefix the id prefix its rules use: {@code L} for the core pack, else its own such as
 *     {@code ACME}
 * @param description one line
 * @param ruleFiles the rule files to load; empty means every YAML or JSON file under {@code rules/}
 */
public record PackManifest(
        String name,
        String version,
        int factModel,
        int rulesSchema,
        String prefix,
        String description,
        List<String> ruleFiles) {

    /** The name of the pack that ships inside the jar. */
    public static final String CORE = "core";

    public PackManifest {
        ruleFiles = List.copyOf(ruleFiles);
    }

    public boolean core() {
        return CORE.equals(name);
    }

    /** {@code name@version}, how a finding says where it came from. */
    public String label() {
        return name + "@" + version;
    }
}
