package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.security.AuditReport;
import io.github.llm4j.loom.security.SecurityAudit;
import io.github.llm4j.loom.security.Severity;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code weave audit}: a security review of a script, with findings mapped to the OWASP Top 10 for LLM Applications. Nothing is run. */
@Command(name = "audit", description = "Reviews a script's security without running it: what each agent can reach, where untrusted content, private data and a way out meet in one agent, unapproved effects, budgets and outside dependencies. Findings name the OWASP Top 10 for LLM Applications risks.")
final class AuditCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(names = "--format", description = "md (default) or json.", defaultValue = "md")
    String format;

    @Option(names = "--out", description = "Also write the report to this file.")
    File out;

    @Option(names = "--fail-on", description = "Exit 1 when a finding is at least this severe: high (default), medium, low, info or none.", defaultValue = "high")
    String failOn;

    @picocli.CommandLine.Mixin
    PromptOptions promptOptions = new PromptOptions();

    @Override
    public Integer call() {
        WeaveEnv env = promptOptions.apply(WeaveEnv.system());
        return env == null ? 2 : audit(this, env);
    }

    static int audit(AuditCommand c, WeaveEnv env) {
        if (!c.format.equals("md") && !c.format.equals("json")) {
            env.err().println("Error: --format takes md or json.");
            return 2;
        }
        Severity threshold = null;
        if (!c.failOn.equalsIgnoreCase("none")) {
            try {
                threshold = Severity.valueOf(c.failOn.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                env.err().println("Error: --fail-on takes high, medium, low, info or none.");
                return 2;
            }
        }
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(c.script.getAbsolutePath());
        } catch (Exception e) {
            env.err().println("Error: " + c.script + " could not be read: " + e.getMessage());
            return 2;
        }
        AuditReport report = SecurityAudit.audit(loaded, c.script.getName());
        var catalog = io.github.llm4j.loom.prompt.PromptSupport.catalog(loaded, c.script.toPath(), env.prompts());
        if (catalog != null) report = report.withPrompts(catalog.usesOf(loaded));
        String text = c.format.equals("json") ? report.json() : report.markdown();
        env.out().println(text);
        if (c.out != null) {
            try {
                Files.writeString(c.out.toPath(), text);
            } catch (Exception e) {
                env.err().println("Error: could not write " + c.out + ": " + e.getMessage());
                return 2;
            }
        }
        return threshold != null && report.failsAt(threshold) ? 1 : 0;
    }
}
