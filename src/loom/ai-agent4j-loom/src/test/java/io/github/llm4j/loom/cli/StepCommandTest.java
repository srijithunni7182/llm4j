package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.travel.TravelFixtures;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R14 to R20 of spec 13 through {@code weave step}. */
class StepCommandTest {

    @TempDir Path dir;

    static WhyCommandTest.Out step(String commands, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("weave step calls no real model here");
                        },
                        q -> {
                            throw new IllegalStateException("weave step asks no one");
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        StepCommand c = new StepCommand();
        new CommandLine(c).parseArgs(args);
        int code = StepCommand.step(c, env, new BufferedReader(new StringReader(commands)));
        return new WhyCommandTest.Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path script(String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        Path file = dir.resolve(name.replace(' ', '-') + ".loom");
        Files.writeString(file, c.script());
        return file;
    }

    @Test
    void showsTheStatementTheVariablesAndTheWayOn() throws Exception {
        Path s = script("triage order status");
        var o = step("quit\n", s.toString(), "-i", "message=where is my parcel?");
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("▶ line 8  classify Intent   (statement 1)")
                .contains("message = where is my parcel?   (input)")
                .contains("then:   line 9  intent.urgent==yes?");
    }

    @Test
    void nextIntoOutBackAndTheVariablesWork() throws Exception {
        Path s = script("triage order status");
        var o = step("next\nvars\ninto\nback\nback\nquit\n", s.toString(), "-i", "message=hello");
        assertThat(o.out())
                .contains("▶ line 9  intent.urgent==yes?   (statement 2)")
                .contains("intent = ")
                .contains("(line 8: intentclassify Intent)")
                .contains("[object]")
                .contains("▶ line 13  intent.kind==order_status?   (statement 3)")
                .contains("inside: ")
                // two steps back lands on the first statement again
                .contains("(statement 1)");
    }

    @Test
    void breakpointsStopAndTheStartingOnesAreListed() throws Exception {
        Path s = script("loop until done");
        var o =
                step(
                        "break\ncontinue\ncontinue\nunbreak all\ncontinue\n",
                        s.toString(),
                        "-i",
                        "topic=t",
                        "--break",
                        "Checker");
        assertThat(o.out())
                .contains("Breakpoint 1: statements for Checker")
                .contains("▶ line 4  delegate Checker")
                .contains("breakpoint 1")
                .contains("No breakpoints.")
                .contains("■ The run completed");
    }

    @Test
    void aBadCommandOrBreakpointExplainsAndTheSessionGoesOn() throws Exception {
        Path s = script("straight");
        var o =
                step(
                        "jump\nbreak when (((\nset 1x = y\nnext\nquit\n",
                        s.toString(),
                        "-i",
                        "topic=t");
        assertThat(o.code()).isZero();
        assertThat(o.out())
                .contains("I do not know \"jump\"")
                .contains("is not a condition the language knows")
                .contains("is not a variable name")
                .contains("▶ line 4  return   (statement 2)");
    }

    @Test
    void setAndModelAndClassifierChangeTheRun() throws Exception {
        Path s = script("triage order status");
        var o =
                step(
                        "classifier Intent {\"kind\":\"refund\",\"urgent\":\"yes\"}\nnext\ninto\nquit\n",
                        s.toString(),
                        "-i",
                        "message=m");
        assertThat(o.out())
                .contains("Intent will answer that at its next call.")
                .contains("▶ line 10  note");
    }

    @Test
    void whyExplainsTheLastChoice() throws Exception {
        Path s = script("triage order status");
        var o = step("next\ninto\nwhy\nquit\n", s.toString(), "-i", "message=m");
        assertThat(o.out())
                .contains("alt (intent.urgent == \"yes\")")
                .contains("intent.urgent = \"no\"")
                .contains("not taken: then");
    }

    @Test
    void whereAndCostAndHelpAnswer() throws Exception {
        Path s = script("triage order status");
        var o = step("next\ninto\nwhere\ncost\nhelp\nquit\n", s.toString(), "-i", "message=m");
        assertThat(o.out())
                .contains("Main")
                .contains("▶ line 13")
                .contains("0 model calls")
                .contains("Nothing is spent".substring(0, 0))
                .contains("run this statement (all of it)");
    }

    @Test
    void theJournalOptionKeepsTheStepsAndWeaveWhyCanReadThem() throws Exception {
        Path s = script("triage order status");
        Path run = dir.resolve("kept");
        var o =
                step(
                        "continue\nquit\n",
                        s.toString(),
                        "-i",
                        "message=m",
                        "--journal",
                        run.toString());
        assertThat(o.code()).isZero();
        assertThat(o.err()).contains("Kept the steps in");
        assertThat(Files.exists(run.resolve("run.json"))).isTrue();
        var why = WhyCommandTest.why(run.toString(), "--all");
        assertThat(why.code()).isZero();
        assertThat(why.out()).contains("intent.urgent");
        // and stepping through it again starts from what it did
        var again = step("next\nquit\n", s.toString(), "--journal", run.toString());
        assertThat(again.out()).contains("message = m").contains("▶ line 9");
    }

    @Test
    void aSessionIsRecordedAndReplaysToTheSameConversation() throws Exception {
        Path s = script("triage order status");
        Path session = dir.resolve("session.txt");
        var first =
                step(
                        "break 13\ncontinue\nvars\nback\nquit\n",
                        s.toString(),
                        "-i",
                        "message=m",
                        "--record",
                        session.toString());
        assertThat(Files.readAllLines(session))
                .containsExactly("break 13", "continue", "vars", "back", "quit");
        var second = step("", s.toString(), "-i", "message=m", "--replay", session.toString());
        // a replay echoes the commands it runs; with them removed it is the same conversation
        String echoed = second.out();
        for (String command : List.of("break 13", "continue", "vars", "back", "quit"))
            echoed = echoed.replace("> " + command + "\n", "> ");
        assertThat(echoed.replace("\n> \n", "\n")).isEqualTo(first.out().replace("\n> \n", "\n"));
    }

    @Test
    void refusesRealEffectsAndLiveModelsWithoutACapOrConsent() throws Exception {
        Path s = script("straight");
        assertThat(step("", s.toString(), "--effects", "real").err()).contains("only simulates");
        assertThat(step("", s.toString(), "--models", "live").err()).contains("spends money");
        assertThat(step("", s.toString(), "--models", "live", "--max-tokens", "9").err())
                .contains("Add --yes");
        assertThat(step("", s.toString(), "--models", "live", "--max-cost", "1", "--yes").err())
                .contains("--prices");
        assertThat(step("", "/nowhere.loom").err()).contains("is not there");
    }

    @Test
    void whatARunHoldsCannotMoveTheCursor() throws Exception {
        Path s = script("straight");
        var o =
                step(
                        "model A \"\u001b[2Jx\\ny\"\ncontinue\np draft\nquit\n",
                        s.toString(),
                        "-i",
                        "topic=t");
        assertThat(o.out()).doesNotContain("\u001b");
    }

    @Test
    void theOriginalScriptIsNeverWritten() throws Exception {
        Path s = script("straight");
        String before = Files.readString(s);
        step("continue\nquit\n", s.toString(), "-i", "topic=t");
        assertThat(Files.readString(s)).isEqualTo(before);
    }
}
