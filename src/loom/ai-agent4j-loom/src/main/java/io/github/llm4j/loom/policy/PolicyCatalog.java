package io.github.llm4j.loom.policy;

import io.github.llm4j.agent.prompt.MarkdownFolderPromptRegistry;
import io.github.llm4j.loom.ast.PolicyDef;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the policy files of a script: the {@code policies/} folder beside it, resolved exactly as
 * {@code prompts/} is (the latest {@code vN.md}, or the version a reference pins with {@code @v2}).
 * A policy file is never part of any prompt; nothing here hands its text to an agent.
 */
public final class PolicyCatalog {

    /**
     * A policy as it was resolved.
     *
     * @param name the script's name for it
     * @param version the file's version, as the registry reports it
     * @param hash a hash of the rules, recorded with every check so a run says which wording it
     *     was checked against
     * @param rules the rules in file order
     */
    public record Resolved(String name, String version, String hash, List<PolicyFile.Rule> rules) {}

    private final PromptCatalog catalog;
    private final int maxRuleChars;

    /** A catalog over the {@code policies/} folder at {@code dir}. */
    public PolicyCatalog(Path dir, int maxRuleChars) {
        this.catalog =
                new PromptCatalog(new MarkdownFolderPromptRegistry(dir), Map.of());
        this.maxRuleChars = maxRuleChars;
    }

    /** The policy folder beside a script. */
    public static PolicyCatalog beside(Path scriptDir) {
        return new PolicyCatalog(
                scriptDir.resolve("policies"), PolicyFile.DEFAULT_MAX_RULE_CHARS);
    }

    /**
     * Why a policy cannot be used, in words for the person who wrote the script, or null when it
     * can.
     */
    public String problemWith(PolicyDef def) {
        String missing = catalog.problemWith(def.getReference(), "policy");
        if (missing != null) return missing;
        Optional<PromptCatalog.Resolved> file = catalog.resolve(def.getReference());
        if (file.isEmpty()) return "policy " + def.getReference() + " was not found";
        PolicyFile parsed = PolicyFile.parse(file.get().text(), maxRuleChars);
        return parsed.usable()
                ? null
                : "policy " + def.getName() + ": " + String.join("; ", parsed.problems());
    }

    /** The resolved policy, or empty when {@link #problemWith} would say why not. */
    public Optional<Resolved> resolve(PolicyDef def) {
        Optional<PromptCatalog.Resolved> file = catalog.resolve(def.getReference());
        if (file.isEmpty()) return Optional.empty();
        PolicyFile parsed = PolicyFile.parse(file.get().text(), maxRuleChars);
        if (!parsed.usable()) return Optional.empty();
        return Optional.of(
                new Resolved(
                        def.getName(),
                        file.get().version(),
                        hash(parsed.rules()),
                        parsed.rules()));
    }

    private static String hash(List<PolicyFile.Rule> rules) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (PolicyFile.Rule r : rules) {
                digest.update(r.id().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(r.text().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }
}
