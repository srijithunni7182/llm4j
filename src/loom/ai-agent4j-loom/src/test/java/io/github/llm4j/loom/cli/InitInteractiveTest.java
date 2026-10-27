package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.init.Templates;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * R23 to R25 of spec 16: one question on a terminal, the old usage without one, and starters that
 * pass the gates.
 */
class InitInteractiveTest {

    @TempDir Path dir;

    record Out(int code, String out, String err, List<String> asked) {}

    private Out init(
            boolean terminal, Map<String, String> vars, List<String> answers, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Deque<String> queue = new ArrayDeque<>(answers);
        List<String> asked = new java.util.ArrayList<>();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("init calls no model");
                        },
                        q -> {
                            asked.add(q);
                            return queue.isEmpty() ? "" : queue.poll();
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8),
                        Clock.systemUTC(),
                        d -> {},
                        c ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        vars::get);
        InitCommand c = new InitCommand();
        new CommandLine(c).parseArgs(args);
        int code = InitCommand.init(c, env, terminal);
        return new Out(
                code,
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8),
                asked);
    }

    @Test
    void withoutATerminalItPrintsTheUsageAsItAlwaysDid() {
        Out r = init(false, Map.of(), List.of("route tickets"));
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.asked()).isEmpty();
        assertThat(r.out()).contains("Templates for weave init").contains("classifier");
    }

    @Test
    void aSentenceOffersGeneratingATemplateOrTheDemo() {
        Out r =
                init(
                        true,
                        Map.of(),
                        List.of("route support tickets and reply to the simple ones", "3"));
        assertThat(r.code()).isZero();
        assertThat(r.asked()).hasSize(2);
        assertThat(r.out())
                .contains(
                        "1) Generate it      weave generate \"route support tickets and reply to the simple ones\"")
                .contains("2) Start from the 'classifier' template")
                .contains("3) Look at a demo first      weave try")
                .contains("Run: weave try");
    }

    @Test
    void generatingWithoutAKeyExplainsAndPointsAtDoctorWhileTheOtherOptionsNeedNone() {
        Out none = init(true, Map.of(), List.of("a pipeline that writes", "1"));
        assertThat(none.out())
                .contains("needs a model key, and none is set")
                .contains("weave doctor")
                .contains("Options 2 and 3 need no key");
        Out keyed =
                init(true, Map.of("GEMINI_API_KEY", "x"), List.of("a pipeline that writes", "1"));
        assertThat(keyed.out())
                .contains("Run: weave generate \"a pipeline that writes\"")
                .doesNotContain("none is set");
    }

    @Test
    void choosingTheTemplateCreatesTheProjectItGuessed() throws Exception {
        Path target = dir.resolve("tickets");
        InitCommand c = new InitCommand();
        c.flat = true;
        c.dir = target.toFile();
        Deque<String> answers = new ArrayDeque<>(List.of("route support tickets", "2"));
        WeaveEnv env =
                new WeaveEnv(
                        m -> null,
                        q -> answers.poll(),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        new PrintStream(new ByteArrayOutputStream(), true),
                        Clock.systemUTC(),
                        d -> {},
                        r ->
                                new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                        0, "", ""),
                        List.of("weave"),
                        k -> null);
        assertThat(InitCommand.init(c, env, true)).isZero();
        assertThat(c.template).isEqualTo("classifier");
        assertThat(target.resolve("main.loom")).exists();
        assertThat(InitCommand.guess("someone approves the refund")).isEqualTo("approval");
        assertThat(InitCommand.guess("write a newsletter")).isEqualTo("pipeline");
    }

    @Test
    void pressingEnterListsTheTemplatesAndTakesTheNamedOne() {
        Out r = init(true, Map.of(), List.of("", "nope"));
        assertThat(r.out()).contains("Templates:").contains("pipeline");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("no template named nope");
        assertThat(init(true, Map.of(), List.of("", "")).err()).contains("no template chosen");
    }

    @Test
    void everyStarterChecksCleanAndHasNoHighFinding() throws Exception {
        for (Templates.Template t : Templates.all()) {
            Path target = dir.resolve("gate-" + t.name());
            Out made = init(false, Map.of(), List.of(), t.name(), target.toString(), "--flat");
            assertThat(made.code()).as(t.name() + made.err()).isZero();
            Path script = target.resolve("main.loom");
            assertThat(Files.exists(script)).as(t.name()).isTrue();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            WeaveEnv quiet =
                    new WeaveEnv(
                            m -> null,
                            q -> "",
                            new PrintStream(out, true),
                            new PrintStream(err, true),
                            Clock.systemUTC(),
                            d -> {},
                            c ->
                                    new io.github.llm4j.loom.trigger.system.CommandRunner.Result(
                                            0, "", ""),
                            List.of("weave"),
                            k -> null);
            int code =
                    WeaveCLI.check(
                            script.toFile(),
                            null,
                            new WeaveCLI.CheckSettings(false, false, true, false),
                            quiet);
            assertThat(code).as(t.name() + " check: " + err).isZero();
            ByteArrayOutputStream rep = new ByteArrayOutputStream();
            WeaveEnv review =
                    quiet.withStreams(
                            new PrintStream(rep, true, StandardCharsets.UTF_8),
                            new PrintStream(new ByteArrayOutputStream(), true));
            ReviewCommand rc = new ReviewCommand();
            new CommandLine(rc).parseArgs(script.toString(), "--format", "json", "--no-env");
            ReviewCommand.review(rc, review);
            JsonNode r = new ObjectMapper().readTree(rep.toString(StandardCharsets.UTF_8));
            for (JsonNode f : r.path("findings"))
                assertThat(f.path("severity").asText())
                        .as(t.name() + " " + f.path("rule").asText())
                        .isNotEqualTo("high");
        }
    }
}
