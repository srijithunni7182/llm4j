package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The audit rules of the sandbox (phase 35, D19): a program run at a weak isolation (LA31), a
 * workflow that reaches a process it started (LA32), and a sandbox's way out to the network (LA33).
 * They read the declarations only: what the sandbox's backend enforces is checked when it loads.
 */
final class SandboxAudit {

    /** Programs that run whatever they are given: allowing one allows any command. */
    static final Set<String> INTERPRETERS =
            Set.of("sh", "bash", "zsh", "dash", "python", "python3", "node", "deno", "bun", "ruby", "perl", "php", "java", "pwsh", "powershell", "env", "xargs", "npx", "uv", "uvx");

    private SandboxAudit() {}

    static void findings(LoomScript script, List<Finding> findings) {
        for (ToolDef t : script.getTools()) {
            if (t.getKind().equals("sandbox")) sandbox(t, findings);
            else if (t.getKind().equals("http") && t.getOptions().containsKey("reaches")) reaches(t, findings);
        }
    }

    private static String option(ToolDef t, String name) {
        ToolDef.OptionValue v = t.getOptions().get(name);
        return v == null ? null : v.toString();
    }

    private static void sandbox(ToolDef t, List<Finding> findings) {
        String isolation = option(t, "isolation");
        String network = option(t, "network");
        if (network == null) network = "none";
        List<String> secrets = new ArrayList<>();
        for (Map.Entry<String, ToolDef.OptionValue> e : t.getOptions().entrySet())
            if (e.getKey().startsWith("env.")) secrets.add(e.getKey().substring(4) + " from " + e.getValue());
        if ("process".equals(isolation) || "none".equals(isolation)) {
            boolean open = network.equals("open");
            boolean secret = !secrets.isEmpty();
            Severity severity = open && secret ? Severity.HIGH : Severity.MEDIUM;
            findings.add(
                    new Finding(
                            "LA31",
                            severity,
                            List.of(Owasp.LLM06, Owasp.LLM02),
                            "tool " + t.getName(),
                            t.getLine(),
                            "A program runs without a sandbox",
                            "The tool " + t.getName() + " runs " + programs(t) + " at isolation " + isolation
                                    + ": a child process of the run, with the run's own user, files and network. It can read anything that user can"
                                    + (open ? ", reach any host" : "")
                                    + (secret ? ", and it is given " + String.join(", ", secrets) : "")
                                    + ". The time and the output are bounded; nothing else is.",
                            "Use isolation: container for anything a model chooses to run; keep process for trying a workflow on your own machine."));
        }
        if (network.equals("open") || network.equals("allow")) {
            String hosts = option(t, "hosts");
            boolean open = network.equals("open");
            List<String> wide = new ArrayList<>();
            if (hosts != null)
                for (String h : hosts.split(",")) if (h.strip().startsWith("*.")) wide.add(h.strip());
            findings.add(
                    new Finding(
                            "LA33",
                            open ? Severity.MEDIUM : Severity.INFO,
                            List.of(Owasp.LLM02, Owasp.LLM06),
                            "tool " + t.getName(),
                            t.getLine(),
                            open ? "A sandbox reaches any host" : "A sandbox reaches listed hosts",
                            open
                                    ? "The tool " + t.getName() + " has network: open: a program it runs can send what it reads to any host."
                                    : "The tool " + t.getName() + " reaches " + hosts + " through the egress proxy, and nothing else"
                                            + (wide.isEmpty() ? "." : "; " + String.join(", ", wide) + " is wide: anyone who can publish under it can receive what the program sends."),
                            open
                                    ? "Use network: allow with the hosts the program needs, or network: none."
                                    : "Keep the list to the hosts the program needs; prefer exact names to wildcards."));
        }
    }

    /** The allowed programs, saying when one of them runs any command. */
    private static String programs(ToolDef t) {
        String allow = option(t, "allow");
        if (allow == null) return "its programs";
        List<String> any = new ArrayList<>();
        for (String p : allow.split(",")) if (INTERPRETERS.contains(p.strip())) any.add(p.strip());
        return allow + (any.isEmpty() ? "" : " (" + String.join(", ", any) + " can run any command)");
    }

    private static void reaches(ToolDef t, List<Finding> findings) {
        String owner = option(t, "reaches");
        findings.add(
                new Finding(
                        "LA32",
                        Severity.INFO,
                        List.of(Owasp.LLM06),
                        "tool " + t.getName(),
                        t.getLine(),
                        "A workflow reaches a process it started",
                        "The tool " + t.getName() + " calls the program the sandbox " + owner
                                + " serves, on 127.0.0.1 and that program's one port, only while it runs. Each call is audited as served_port_reached. What the program answers is the program's: treat it as you treat the sandbox's output.",
                        "Nothing to change; check that " + owner + " serves only what the workflow needs to reach."));
    }
}
