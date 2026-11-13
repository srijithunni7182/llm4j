package io.github.llm4j.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolKind;
import io.github.llm4j.agent.tool.ToolProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 35 (D2): each generic kind says what a tool of it reaches, from its options as written. The
 * values are the ones Loom's audit gave these kinds by name before the kinds said it themselves.
 */
class KindProfilesTest {

    private static ToolProfile profile(ToolKind kind, Map<String, String> options) {
        return kind.profile(options);
    }

    private static void reaches(
            ToolProfile p, boolean untrusted, boolean privateData, boolean outward, TaskEffect e) {
        assertThat(p.known()).isTrue();
        assertThat(p.untrusted()).as("untrusted").isEqualTo(untrusted);
        assertThat(p.privateData()).as("private data").isEqualTo(privateData);
        assertThat(p.outward()).as("outward").isEqualTo(outward);
        assertThat(p.effect()).as("effect").isEqualTo(e);
        assertThat(p.journaled()).as("journaled").isTrue();
        assertThat(p.isolation()).isEqualTo(Isolation.NONE);
    }

    @Test
    void aWebhookPostsToAFixedUrl() {
        ToolProfile p = profile(new WebhookKind(), Map.of("url", "env.HOOK"));
        reaches(p, false, false, true, TaskEffect.CHANGES);
        assertThat(p.note()).isEqualTo("posts to a fixed URL");
    }

    @Test
    void emailSaysWhetherTheRecipientsAreFixed() {
        ToolProfile fixed = profile(new EmailKind(), Map.of("from", "a@x", "to", "b@x"));
        reaches(fixed, false, false, true, TaskEffect.CHANGES);
        assertThat(fixed.note()).isEqualTo("mails fixed recipients");
        assertThat(profile(new EmailKind(), Map.of("from", "a@x", "allow_to", "*@x")).note())
                .isEqualTo("mails recipients the agent chooses from allow_to");
    }

    @Test
    void httpReadsUnlessItsMethodsWriteAndHoldsPrivateDataWithCredentials() {
        ToolProfile read = profile(new HttpKind(), Map.of("base_url", "https://a"));
        reaches(read, true, false, false, TaskEffect.READS);
        assertThat(read.note()).isEqualTo("reads from https://a");

        ToolProfile get =
                profile(new HttpKind(), Map.of("base_url", "https://a", "methods", " get , "));
        reaches(get, true, false, false, TaskEffect.READS);

        ToolProfile write =
                profile(new HttpKind(), Map.of("base_url", "https://a", "methods", "GET, POST"));
        reaches(write, true, false, true, TaskEffect.CHANGES);
        assertThat(write.note()).isEqualTo("calls https://a with GET, POST");

        reaches(
                profile(
                        new HttpKind(),
                        Map.of("base_url", "https://a", "auth_value", "secret.TOKEN")),
                true,
                true,
                false,
                TaskEffect.READS);
        reaches(
                profile(new HttpKind(), Map.of("base_url", "https://a", "auth_header", "X-Key")),
                true,
                true,
                false,
                TaskEffect.READS);
    }

    @Test
    void aFileToolReadsByDefaultAndWritesInAnyOtherMode() {
        ToolProfile byDefault = profile(new FileKind(), Map.of());
        reaches(byDefault, false, true, false, TaskEffect.READS);
        assertThat(byDefault.note()).isEqualTo("read in .");

        ToolProfile write = profile(new FileKind(), Map.of("mode", "write", "root", "out"));
        reaches(write, false, false, false, TaskEffect.CHANGES);
        assertThat(write.note()).isEqualTo("write in out");

        reaches(
                profile(new FileKind(), Map.of("mode", "readwrite")),
                false,
                true,
                false,
                TaskEffect.CHANGES);
    }

    @Test
    void shellRunsItsAllowedPrograms() {
        ToolProfile p = profile(new ShellKind(), Map.of("allow", "git, ls"));
        reaches(p, false, true, true, TaskEffect.CHANGES);
        assertThat(p.note()).isEqualTo("runs git, ls");
    }

    @Test
    void sqlRunsReadOnlyQueries() {
        ToolProfile p = profile(new SqlKind(), Map.of("url", "env.DB"));
        reaches(p, false, true, false, TaskEffect.READS);
        assertThat(p.note()).isEqualTo("read-only queries");
    }
}
