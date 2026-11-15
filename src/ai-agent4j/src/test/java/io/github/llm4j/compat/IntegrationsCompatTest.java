package io.github.llm4j.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.tool.Outcome;
import io.github.llm4j.telemetry.ErrorCategory;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 36, D2 and D16 (G92): what stays for existing code. The core OpenAPI classes are deprecated
 * for removal, not removed; {@code credential_rejected} is added to the end of the public enums, so
 * no existing value moves.
 */
class IntegrationsCompatTest {

    @Test
    void theCoreOpenapiClassesAreDeprecatedForRemovalAndStillThere() throws Exception {
        for (String name :
                List.of(
                        "OpenAPITool",
                        "OpenAPIParser",
                        "OpenAPISpec",
                        "OpenAPIEndpoint",
                        "OpenAPIParameter")) {
            Class<?> type = Class.forName("io.github.llm4j.agent.tools.openapi." + name);
            Deprecated deprecated = type.getAnnotation(Deprecated.class);
            assertThat(deprecated).as(name).isNotNull();
            assertThat(deprecated.forRemoval()).as(name).isTrue();
            assertThat(deprecated.since()).as(name).isEqualTo("6.2.0");
        }
    }

    @Test
    void theOldErrorCategoriesKeepTheirPositionsAndTheNewOneIsLast() {
        List<String> before =
                List.of(
                        "INVALID_ARGUMENTS",
                        "TIMEOUT",
                        "NETWORK",
                        "RATE_LIMITED",
                        "AUTHENTICATION",
                        "PERMISSION_DENIED",
                        "NOT_FOUND",
                        "CLIENT_ERROR",
                        "SERVER_ERROR",
                        "CONTENT_BLOCKED",
                        "CONTEXT_OVERFLOW",
                        "BUDGET",
                        "REJECTED_BY_HUMAN",
                        "REPEAT_BLOCKED",
                        "UNKNOWN_TOOL",
                        "APPROVAL_UNAVAILABLE",
                        "OTHER");

        List<String> now = Arrays.stream(ErrorCategory.values()).map(Enum::name).toList();

        assertThat(now.subList(0, before.size())).isEqualTo(before);
        assertThat(now).endsWith("CREDENTIAL_REJECTED");
    }

    @Test
    void theOldOutcomeStatusesKeepTheirPositionsAndTheNewOneIsLast() {
        List<String> now = Arrays.stream(Outcome.Status.values()).map(Enum::name).toList();

        assertThat(now).containsExactly("OK", "FAILED", "UNKNOWN", "CREDENTIAL_REJECTED");
    }

    @Test
    void aCredentialRejectionIsTextTheCallerCanTellApart() {
        Outcome rejected = Outcome.credentialRejected("the server refused it");

        assertThat(rejected.status()).isEqualTo(Outcome.Status.CREDENTIAL_REJECTED);
        assertThat(rejected.text()).startsWith("Error: credential_rejected: ");
    }
}
