package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.generic.support.ScriptedRun;
import io.github.llm4j.loom.prompt.PromptSupport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The approval starter keeps the rule in the script: whatever the model says, an over-the-limit refund (or one whose amount was not read) is put to a
 * person, and a small refund or a question is not. The model only reports the kind and the amount.
 */
class ApprovalTemplateTest {

    @TempDir Path dir;

    private ScriptedRun run;
    private int projects;
    private Path project;

    HarnessExecutor started(String... modelReplies) throws Exception {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true);
        WeaveEnv env = new WeaveEnv(m -> { throw new IllegalStateException("no models"); }, q -> "yes", sink, sink, Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
        InitCommand c = new InitCommand();
        project = dir.resolve("p" + (++projects));
        new CommandLine(c).parseArgs("approval", project.toString());
        assertThat(InitCommand.init(c, env)).isZero();
        Path main = project.resolve("main.loom");
        LoomScript script = new LoomLoader().load(main.toString());
        run = new ScriptedRun(project);
        run.replies(modelReplies);
        HarnessExecutor e = run.executor(script);
        e.setPromptCatalog(PromptSupport.catalog(script, main, null));
        e.initialize();
        return e;
    }

    static String triage(String kind, String amount, String stated) {
        return ScriptedRun.done("{\"kind\": \"" + kind + "\", \"amount\": " + amount + ", \"amount_stated\": \"" + stated + "\"}");
    }

    String ask(String email, String... modelReplies) throws Exception {
        HarnessExecutor e = started(modelReplies);
        e.executeWorkflow("Main", Map.of("email", email));
        return String.join("\n", run.questions);
    }

    @Test
    void aRefundOverTheLimitIsPutToAPersonWithTheReasonAndTheDraft() throws Exception {
        String asked = ask("I was charged twice, 240 dollars.", triage("REFUND", "240", "YES"), ScriptedRun.done("We are sorry."));
        assertThat(asked).contains("over the 100 dollar limit").contains("We are sorry.").contains("Approve it?");
    }

    @Test
    void aRefundWhoseAmountWasNotReadIsPutToAPersonToo() throws Exception {
        String asked = ask("I want my money back.", triage("REFUND", "0", "NO"), ScriptedRun.done("We are sorry."));
        assertThat(asked).contains("No amount could be read").contains("Approve it?");
    }

    @Test
    void aSmallRefundAndAQuestionAreNotAskedAbout() throws Exception {
        assertThat(ask("Charged twice, 40 dollars.", triage("REFUND", "40", "YES"), ScriptedRun.done("Done."))).isEmpty();
        assertThat(ask("Opening hours?", triage("QUESTION", "0", "NO"), ScriptedRun.done("9 to 5."))).isEmpty();
    }

    @Test
    void theLimitIsTheScriptsAndExactlyOneHundredIsNotOverIt() throws Exception {
        assertThat(ask("Exactly 100 dollars.", triage("REFUND", "100", "YES"), ScriptedRun.done("Done."))).isEmpty();
        assertThat(ask("101 dollars.", triage("REFUND", "101", "YES"), ScriptedRun.done("Done."))).contains("over the 100 dollar limit");
    }

    @Test
    void theTriagePromptDoesNotLetTheModelDecideWhoApproves() throws Exception {
        started();
        String prompt = java.nio.file.Files.readString(project.resolve("prompts/triage.md"));
        assertThat(prompt).doesNotContain("needs_approval").contains("never decide").contains("amount");
    }
}
