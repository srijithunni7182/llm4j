package io.github.llm4j.loom.ast;

/**
 * AST node holding audio/audit configuration declared at the script level.
 *
 * <p>Example {@code .loom} syntax:
 * <pre>
 * audit {
 *     logger: "file"
 *     path: "./logs/loom-audit.jsonl"
 * }
 * </pre>
 */
public class AuditConfig implements Node {
    /** "file" | "noop" (default: "noop") */
    private String logger = "noop";
    private String path   = "./loom-audit.jsonl";

    public String getLogger() { return logger; }
    public String getPath()   { return path; }

    public void setLogger(String logger) { this.logger = logger; }
    public void setPath(String path)     { this.path = path; }
}
