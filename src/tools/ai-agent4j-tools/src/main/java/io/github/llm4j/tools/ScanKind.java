package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Scan { use: scan }}: finds credentials and personal data in text, or in files under
 * {@code root:}, and reports a rule and a place for each, never the match (phase 35, D14). A gate
 * before something is sent or published. It is a net, not a proof: a clean result means nothing the
 * rules know was found.
 */
public final class ScanKind extends GenericKind {

    static final long DEFAULT_MAX_BYTES = 1024 * 1024;
    static final long MAX_BYTES_CEILING = 16L * 1024 * 1024;
    static final int DEFAULT_MAX_FILES = 1000;
    static final int MAX_FILES_CEILING = 20_000;
    static final int DEFAULT_MAX_FINDINGS = 100;
    static final int MAX_FINDINGS_CEILING = 5000;

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public Set<String> optional() {
        return Set.of("root", "rules", "pii", "max_bytes", "max_files", "max_findings");
    }

    /** {@code rules:} secrets, pii or both (the default). */
    static Set<String> rules(Options o) {
        List<String> rules = o.has("rules") ? o.list("rules") : List.of("secrets", "pii");
        for (String r : rules)
            if (!r.equals("secrets") && !r.equals("pii"))
                throw new OptionException("rules: takes secrets and pii, not " + r);
        return new LinkedHashSet<>(rules);
    }

    /** {@code pii:} the kinds of personal data looked for. */
    static Set<String> pii(Options o) {
        if (!o.has("pii")) return ScanRules.DEFAULT_PII;
        Set<String> out = new LinkedHashSet<>();
        for (String k : o.list("pii")) {
            if (!ScanRules.PII_KINDS.contains(k))
                throw new OptionException(
                        "pii: takes "
                                + String.join(", ", new java.util.TreeSet<>(ScanRules.PII_KINDS))
                                + ", not "
                                + k);
            out.add(k);
        }
        return out;
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        rules(o);
        pii(o);
        o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING);
        o.integer("max_files", DEFAULT_MAX_FILES, 1, MAX_FILES_CEILING);
        o.integer("max_findings", DEFAULT_MAX_FINDINGS, 1, MAX_FINDINGS_CEILING);
        if (o.has("root")) RootedFiles.resolveRoot(o.get("root"), baseDir);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Path root = o.has("root") ? RootedFiles.resolveRoot(o.get("root"), baseDir) : null;
        Set<String> rules = rules(o);
        return new ScanTool(
                name,
                new RootedFiles(root, o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING)),
                root,
                rules.contains("secrets"),
                rules.contains("pii") ? pii(o) : Set.of(),
                o.integer("max_files", DEFAULT_MAX_FILES, 1, MAX_FILES_CEILING),
                o.integer("max_findings", DEFAULT_MAX_FINDINGS, 1, MAX_FINDINGS_CEILING),
                context);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        String net = "; reports a rule and a place, never the match; a net, not a proof";
        String root = options.get("root");
        return root == null
                ? ToolProfile.pure("scans text it is given for secrets and personal data" + net)
                : ToolProfile.of(false, true, false, false, "scans files in " + root + net);
    }

    /** File names a finding points at may have been chosen by a model. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("findings");
    }
}
