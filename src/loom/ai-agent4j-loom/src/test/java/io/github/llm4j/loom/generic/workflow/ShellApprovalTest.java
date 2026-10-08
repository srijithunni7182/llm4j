package io.github.llm4j.loom.generic.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoadException;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How an agent may use a {@code shell} tool: approval or {@code unattended}, enforced by the Loom executor. */
class ShellApprovalTest {

    @TempDir
    Path dir;

    static final String AGENT = """
            tool Ops { use: shell  allow: "touch"  %s }
            agent Operator { model: "m" tools: [Ops] %s }
            workflow Main() { delegate "make the file" to Operator -> result }
            """;

    ScriptedRun scripted() {
        return new ScriptedRun(dir);
    }

    @Test
    @Tag("V8.10")
    void anAgentMustApproveAShellToolOrTheToolMustSayItIsUnattended() {
        assertThatThrownBy(() -> scripted().executor(AGENT.formatted("", "")).initialize())
                .isInstanceOf(LoomLoadException.class).hasMessageContaining("add it to Operator's approve: list").hasMessageContaining("unattended: true");
        assertThatThrownBy(() -> scripted().executor(AGENT.formatted("", "approve: [Other]")).initialize()).isInstanceOf(LoomLoadException.class);

        scripted().executor(AGENT.formatted("", "approve: [Ops]")).initialize();
        scripted().executor(AGENT.formatted("", "approve: all")).initialize();
        scripted().executor(AGENT.formatted("unattended: true", "")).initialize();
    }

    @Test
    @Tag("V8.11")
    void aRejectedApprovalMeansTheProgramDoesNotRun() throws Exception {
        ScriptedRun run = scripted().replies(ScriptedRun.call("Ops", "{\"program\": \"touch\", \"args\": [\"made.txt\"]}"), ScriptedRun.done("finished"));
        run.human = question -> "no";
        HarnessExecutor executor = run.executor(AGENT.formatted("", "approve: [Ops]"));
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        assertThat(run.questions).anyMatch(q -> q.contains("wants to call Ops"));
        assertThat(dir.resolve("made.txt")).doesNotExist();
    }

    @Test
    @Tag("V8.11")
    void anApprovedCallRunsTheProgram() throws Exception {
        ScriptedRun run = scripted().replies(ScriptedRun.call("Ops", "{\"program\": \"touch\", \"args\": [\"made.txt\"]}"), ScriptedRun.done("finished"));
        HarnessExecutor executor = run.executor(AGENT.formatted("", "approve: [Ops]"));
        executor.initialize();
        executor.executeWorkflow("Main", Map.of());

        assertThat(dir.resolve("made.txt")).exists();
        assertThat(run.audit).contains("approval_granted", "tool_effect");
    }
}
