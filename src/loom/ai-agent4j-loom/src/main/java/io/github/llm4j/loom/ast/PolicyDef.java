package io.github.llm4j.loom.ast;

/**
 * {@code policy HouseRules: "house-rules" checked by Compliance}: rules that live in their own
 * markdown file under {@code policies/}, never in a prompt (docs/specs/26-policy-boundary, R1). The
 * script names the policy and, optionally, the evaluator that checks an agent's own changing tool
 * calls against it.
 */
public final class PolicyDef implements Node {

    private final String name;
    private final String reference;
    private final String checkedBy;
    private final int line;

    /**
     * Creates a policy.
     *
     * @param name the name used in {@code check … against}
     * @param reference the file id, optionally with a version: {@code house-rules} or {@code
     *     legal@v2}
     * @param checkedBy the evaluator that checks agents' own changing tool calls, or null
     * @param line the 1-based line of the declaration, or 0
     */
    public PolicyDef(String name, String reference, String checkedBy, int line) {
        this.name = java.util.Objects.requireNonNull(name, "name");
        this.reference = java.util.Objects.requireNonNull(reference, "reference");
        this.checkedBy = checkedBy;
        this.line = line;
    }

    /** The name used after {@code against}. */
    public String getName() {
        return name;
    }

    /** The file id as written, with its {@code @vN} when pinned. */
    public String getReference() {
        return reference;
    }

    /** The evaluator named by {@code checked by}, or null. */
    public String getCheckedBy() {
        return checkedBy;
    }

    /** The 1-based line of the declaration, or 0. */
    public int getLine() {
        return line;
    }
}
