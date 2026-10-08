package io.github.llm4j.loom.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The result of {@link SecurityAudit}: who can do what, what was found, and how it lines up with the OWASP Top 10 for LLM Applications. */
public record AuditReport(String script, List<SecurityAudit.AgentProfile> agents, List<Finding> findings, Map<Owasp, List<String>> controls,
                          List<io.github.llm4j.loom.prompt.PromptUse> prompts) {

    public AuditReport {
        prompts = prompts == null ? List.of() : List.copyOf(prompts);
    }

    public AuditReport(String script, List<SecurityAudit.AgentProfile> agents, List<Finding> findings, Map<Owasp, List<String>> controls) {
        this(script, agents, findings, controls, List.of());
    }

    /** The same report listing which prompt each agent would run (id, version and a hash, never the text). */
    public AuditReport withPrompts(List<io.github.llm4j.loom.prompt.PromptUse> uses) {
        return new AuditReport(script, agents, findings, controls, uses);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public long count(Severity s) {
        return findings.stream().filter(f -> f.severity() == s).count();
    }

    /** Whether anything at or above {@code threshold} was found. */
    public boolean failsAt(Severity threshold) {
        return findings.stream().anyMatch(f -> f.severity().atLeast(threshold));
    }

    public String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("# Security audit: ").append(script).append("\n\n");
        b.append("**").append(findings.size()).append(findings.size() == 1 ? " finding" : " findings").append("**: ")
                .append(count(Severity.HIGH)).append(" high, ").append(count(Severity.MEDIUM)).append(" medium, ")
                .append(count(Severity.LOW)).append(" low, ").append(count(Severity.INFO)).append(" info.\n\n");
        b.append(count(Severity.HIGH) > 0 ? "> Fix the high findings before this script runs unattended.\n\n"
                : count(Severity.MEDIUM) > 0 ? "> No high findings. Review the medium ones.\n\n" : "> No high or medium findings.\n\n");

        b.append("## What each agent can do\n\n");
        b.append("The *lethal trifecta* is one agent that reads untrusted content, reaches private data and can send or act. ")
                .append("Text it reads can then tell it to send your data out.\n\n");
        b.append("| Agent | Reads untrusted content | Reaches private data | Can send or act | Effects without approval | Budget | PII guard | Trifecta |\n");
        b.append("|---|---|---|---|---|---|---|---|\n");
        for (SecurityAudit.AgentProfile p : agents) {
            b.append("| ").append(p.agent()).append(" (line ").append(p.line()).append(") | ").append(list(p.untrusted())).append(" | ").append(list(p.privateData()))
                    .append(" | ").append(list(p.outward())).append(" | ").append(p.approveAll() ? "none (approve: all)" : list(p.unapprovedEffects()))
                    .append(" | ").append(p.budget()).append(" | ").append(p.pii() == null ? "none" : p.pii())
                    .append(" | ").append(p.trifecta() ? "**yes**" : "no").append(" |\n");
        }
        b.append('\n');

        if (!prompts.isEmpty()) {
            b.append("## Prompts\n\n| Agent | Prompt | Version | Text hash |\n|---|---|---|---|\n");
            for (var u : prompts) {
                b.append("| ").append(u.agent()).append(" (line ").append(u.line()).append(") | ").append(u.reference()).append(" | ")
                        .append(u.resolved() ? u.version() : "**not found**").append(" | ").append(u.resolved() ? u.hash() : "-").append(" |\n");
            }
            b.append('\n');
        }

        b.append("## Findings\n\n");
        if (findings.isEmpty()) b.append("None.\n\n");
        for (Finding f : findings) {
            b.append("### ").append(f.severity().name()).append(" | ").append(f.rule()).append(" | ").append(f.title()).append("\n\n");
            b.append("- **Where:** ").append(f.where()).append(f.line() > 0 ? " (line " + f.line() + ")" : "").append('\n');
            b.append("- **OWASP:** ").append(String.join(", ", f.owasp().stream().map(o -> o.id() + " " + o.title()).toList())).append('\n');
            b.append("- **Risk:** ").append(f.risk()).append('\n');
            b.append("- **Fix:** ").append(f.fix()).append("\n\n");
        }

        b.append("## OWASP Top 10 for LLM Applications (2025)\n\n");
        b.append("| Risk | Findings here | In this script | Always enforced by the runtime |\n|---|---|---|---|\n");
        for (Owasp o : Owasp.values()) {
            long n = findings.stream().filter(f -> f.owasp().contains(o)).count();
            b.append("| ").append(o.id()).append(" ").append(o.title()).append(" | ").append(n).append(" | ")
                    .append(String.join("; ", controls.getOrDefault(o, List.of()))).append(" | ").append(RUNTIME.get(o)).append(" |\n");
        }
        b.append("\n## Not covered by this audit\n\n");
        b.append("It reads the script only. It does not see: what host-registered, Java and MCP tools do; operating-system and database permissions; ")
                .append("what is in indexed documents or memory; provider settings; or the content agents produce. See SECURITY.md for the full threat model.\n");
        return b.toString();
    }

    public String json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("script", script);
        m.put("summary", Map.of("high", count(Severity.HIGH), "medium", count(Severity.MEDIUM), "low", count(Severity.LOW), "info", count(Severity.INFO)));
        List<Map<String, Object>> as = new ArrayList<>();
        for (SecurityAudit.AgentProfile p : agents) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("agent", p.agent());
            a.put("line", p.line());
            a.put("untrusted", p.untrusted());
            a.put("privateData", p.privateData());
            a.put("outward", p.outward());
            a.put("unapprovedEffects", p.unapprovedEffects());
            a.put("trifecta", p.trifecta());
            a.put("budget", p.budget());
            a.put("pii", p.pii());
            as.add(a);
        }
        m.put("agents", as);
        if (!prompts.isEmpty()) {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (var u : prompts) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("agent", u.agent());
                x.put("line", u.line());
                x.put("prompt", u.reference());
                x.put("version", u.version());
                x.put("hash", u.hash());
                ps.add(x);
            }
            m.put("prompts", ps);
        }
        List<Map<String, Object>> fs = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("rule", f.rule());
            x.put("severity", f.severity().word());
            x.put("owasp", f.owasp().stream().map(Owasp::id).toList());
            x.put("where", f.where());
            x.put("line", f.line());
            x.put("title", f.title());
            x.put("risk", f.risk());
            x.put("fix", f.fix());
            fs.add(x);
        }
        m.put("findings", fs);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String list(List<String> items) {
        return items.isEmpty() ? "-" : String.join(", ", items);
    }

    /** What Loom enforces whatever the script says. */
    static final Map<Owasp, String> RUNTIME = Map.of(
            Owasp.LLM01, "tools only as declared per agent; nothing the model says widens them",
            Owasp.LLM02, "secrets scrubbed from results, errors, traces, journals and audit logs",
            Owasp.LLM03, "no tool, skill or server exists unless the script or host declares it",
            Owasp.LLM04, "none (content is not inspected)",
            Owasp.LLM05, "tool fences: no shell syntax, no header or path injection, read-only SQL, bound parameters",
            Owasp.LLM06, "shell needs approval or an explicit unattended; effects journaled and never repeated; a missing approver blocks",
            Owasp.LLM07, "credentials cannot be written into a script, so not into a prompt",
            Owasp.LLM08, "none (retrieval has no per-user access control)",
            Owasp.LLM09, "none (answers are not checked against sources at run time)",
            Owasp.LLM10, "budgets checked before each model call; every loop and agent bounded; timeouts and size caps on tools");
}
