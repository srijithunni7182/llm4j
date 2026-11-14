package io.github.llm4j.loom.security;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.McpServerDef;
import io.github.llm4j.loom.ast.ToolDef;
import java.util.ArrayList;
import java.util.List;

/**
 * The audit rules of integrations (phase 36, D14): an MCP server that runs without a sandbox
 * (LA31, the rule a {@code sandbox} at weak isolation also raises) and an {@code openapi} tool that
 * exposes a whole API (LA34). They read the declarations only.
 */
final class IntegrationAudit {

    private IntegrationAudit() {}

    static void findings(LoomScript script, List<Finding> findings) {
        for (McpServerDef m : script.getMcpServers()) mcp(m, findings);
        for (ToolDef t : script.getTools())
            if ("openapi".equals(t.getKind()) && !t.getOptions().containsKey("operation"))
                findings.add(wholeApi(t));
    }

    /** An MCP server is a program: with no isolation line, or at none or process, it is not sandboxed. */
    private static void mcp(McpServerDef m, List<Finding> findings) {
        String level = m.getIsolation();
        List<String> secrets = new ArrayList<>();
        m.getEnvValues()
                .forEach(
                        (name, value) -> {
                            if (value.isReference()) secrets.add(name + " from " + value);
                        });
        boolean secret = !secrets.isEmpty();
        String how =
                level == null
                        ? "has no isolation: line, so it runs with this process's whole environment, files and"
                                + " network"
                        : "runs at isolation: "
                                + level
                                + ", a child process of the run with the run's own user, files and network";
        findings.add(
                new Finding(
                        "LA31",
                        secret ? Severity.HIGH : Severity.MEDIUM,
                        List.of(Owasp.LLM03, Owasp.LLM06),
                        "mcp " + m.getName(),
                        m.getLine(),
                        "A program runs without a sandbox",
                        "The MCP server "
                                + m.getName()
                                + " starts `"
                                + m.getCmd()
                                + "`, which "
                                + how
                                + (secret ? "; it is given " + String.join(", ", secrets) : "")
                                + ". Every tool it lists runs with those permissions, and the audit cannot see what they do.",
                        "Give the block isolation: process to scrub its environment and capture its error"
                                + " stream (isolation: container follows with the sandbox), run only servers you"
                                + " have reviewed, and declare what each tool does with effects:."));
    }

    private static Finding wholeApi(ToolDef t) {
        ToolDef.OptionValue spec = t.getOptions().get("spec");
        return new Finding(
                "LA34",
                Severity.MEDIUM,
                List.of(Owasp.LLM06),
                "tool " + t.getName(),
                t.getLine(),
                "An openapi tool exposes a whole API",
                "The tool "
                        + t.getName()
                        + " names no operation:, so an agent that has it can call every operation of "
                        + (spec == null ? "its description" : spec.toString())
                        + ", reads and writes alike, under one effect and one approval.",
                "Declare one tool per operation with operation:, each with its own effect: (weave connect"
                        + " writes them), so an agent gets only the operations it needs.");
    }
}
