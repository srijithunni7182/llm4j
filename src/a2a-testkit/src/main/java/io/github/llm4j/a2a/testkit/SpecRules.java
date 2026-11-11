package io.github.llm4j.a2a.testkit;

import java.util.List;

/**
 * The server-side rules of the A2A 1.0.0 specification that {@link MockA2AAgent} enforces, each
 * traced to the specification's own words. {@code section} is the section of {@code
 * docs/specification.md} at tag v1.0.0 (sha256 087c6f62...377d). A client that breaks a rule is
 * refused as the specification says, so a client that passes the mock obeys these rules; it does
 * not show the client obeys rules this table lacks.
 */
public final class SpecRules {

    /**
     * One rule.
     *
     * @param id a stable id
     * @param section the specification section
     * @param strength MUST, SHOULD or MAY as the specification words it
     * @param text what the specification says, shortened
     */
    public record Rule(String id, String section, String strength, String text) {}

    /** Every rule the mock agent enforces. */
    public static final List<Rule> ALL =
            List.of(
                    new Rule("task-id-exists", "3.4.2", "MUST", "When a client includes a taskId in a Message, it MUST reference an existing task; else TaskNotFoundError"),
                    new Rule("task-ids-are-server-made", "3.4.2", "MUST", "Agents MUST generate a unique taskId for each new task they create"),
                    new Rule("context-inferred", "3.4.3", "MUST", "Agents MUST infer contextId from the task if only taskId is provided"),
                    new Rule("context-mismatch-rejected", "3.4.3", "MUST", "Agents MUST reject messages containing mismatching contextId and taskId"),
                    new Rule("context-never-replaced", "3.4.1", "MUST", "If an agent cannot accept a client-provided contextId, it MUST reject the request and MUST NOT generate a new contextId"),
                    new Rule("generated-context-returned", "3.4.1", "MUST", "If an agent generates a new contextId, it MUST be included in the response"),
                    new Rule("terminal-takes-no-messages", "3.1.1", "MUST", "Messages sent to tasks in a terminal state cannot accept further messages; UnsupportedOperationError"),
                    new Rule("blocking-by-default", "3.2.2", "MUST", "Unless return_immediately is true, the operation MUST wait until a terminal or interrupted state"),
                    new Rule("return-immediately", "3.2.2", "MUST", "With return_immediately the operation MUST return immediately after creating the task"),
                    new Rule("list-sorted-newest-first", "3.1.4", "MUST", "Tasks MUST be sorted by last update time in descending order"),
                    new Rule("next-page-token-always", "3.1.4", "MUST", "nextPageToken MUST always be present; empty string on the final page"),
                    new Rule("list-scoped-to-caller", "13.1", "MUST", "Implementations MUST scope results to the caller's authorized access boundaries"),
                    new Rule("get-scoped-to-caller", "13.1", "MUST", "Get Task MUST verify the authenticated client has access; a foreign task is not revealed"),
                    new Rule("artifacts-omitted-unless-asked", "3.1.4", "MUST", "When includeArtifacts is false the artifacts field MUST be omitted entirely"),
                    new Rule("cancel-idempotent", "3.3.1", "MUST", "Cancel Task operations are idempotent"),
                    new Rule("cancel-terminal-not-cancelable", "3.3.2", "MUST", "TaskNotCancelableError for a task already in a terminal state"),
                    new Rule("version-header-required", "3.6.2", "MUST", "Agents MUST process requests using the requested A2A-Version and return VersionNotSupportedError if unsupported; an empty value means 0.3"),
                    new Rule("required-extension", "3.3.4", "MUST", "An agent errors when a client does not support an extension the card marks required"),
                    new Rule("streaming-unsupported", "3.3.4", "MUST", "If capabilities.streaming is false, SendStreamingMessage and SubscribeToTask MUST return UnsupportedOperationError"),
                    new Rule("error-info", "9.5", "MUST", "A2A-specific errors MUST include a google.rpc.ErrorInfo in the data array with reason and domain a2a-protocol.org"),
                    new Rule("required-fields", "5.7", "SHOULD", "Implementations SHOULD validate REQUIRED fields and reject messages missing them"),
                    new Rule("send-may-dedupe", "3.3.1", "MAY", "Send Message operations MAY be idempotent; agents may use the messageId to detect duplicates"),
                    new Rule("auth-required-explains", "7.6.1", "MUST", "AUTH_REQUIRED: the agent MUST use a Task and include a TaskStatus message explaining the authorization"));

    private SpecRules() {}

    /** Looks a rule up by id. */
    public static Rule byId(String id) {
        return ALL.stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no rule " + id));
    }
}
