package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.travel.ScriptHash;
import io.github.llm4j.loom.wtest.CaseProbe;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R20, R22, R26, R29, R30 of spec 14 through the command: exit codes, formats, the HTML file. */
class RegressCommandTest {

    @TempDir Path dir;

    record Out(int code, String out, String err) {}

    static final String SCRIPT = CaptureCommandTest.SCRIPT;

    private Out regress(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        WeaveEnv env =
                new WeaveEnv(
                        m -> {
                            throw new IllegalStateException("regress calls no model");
                        },
                        q -> {
                            throw new IllegalStateException("nobody to ask");
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
        RegressCommand c = new RegressCommand();
        new CommandLine(c).parseArgs(args);
        int code = RegressCommand.regress(c, env);
        return new Out(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path base() throws Exception {
        Path p = Files.createDirectories(dir.resolve("base"));
        Path script = p.resolve("main.loom");
        Files.writeString(script, SCRIPT);
        return script;
    }

    private Path store(Path script) throws Exception {
        Path store = dir.resolve("store");
        var mapper = new ObjectMapper();
        for (String kind : List.of("order_status", "order_status", "refund")) {
            String id =
                    kind
                            + "-"
                            + Files.list(store.resolve("runs").getParent() == null ? dir : dir)
                                    .count()
                            + System.nanoTime();
            var body = mapper.createObjectNode();
            body.putObject("input").put("message", "m " + id);
            if (kind.equals("order_status")) body.putArray("humans").add("yes");
            var m = body.putObject("models");
            m.putObject("Intent").put("kind", kind).put("urgent", "no");
            m.put("Replier", "On its way.");
            m.put("Escalator", "A person should look.");
            body.put("name", "x");
            var result = CaseProbe.run(script, "Main", body);
            Path run = store.resolve("runs").resolve(id);
            Files.createDirectories(run);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("script", script.toString());
            spec.put("workflow", "Main");
            spec.put("inputs", Map.of("message", "m " + id));
            spec.put("scriptHash", ScriptHash.of(script));
            mapper.writeValue(run.resolve("run.json").toFile(), spec);
            mapper.writeValue(run.resolve("journal.json").toFile(), result.journal());
        }
        return store;
    }

    private Path candidate(String name, String from, String to) throws Exception {
        Path p = Files.createDirectories(dir.resolve(name));
        Path s = p.resolve("main.loom");
        Files.writeString(s, SCRIPT.replace(from, to));
        return s;
    }

    @Test
    void anUnchangedCandidateExitsZeroAndTheReportSaysSo() throws Exception {
        Path base = base();
        Path store = store(base);
        Path same = candidate("same", "zzz-not-there", "");
        Out o =
                regress(
                        same.toString(),
                        "--baseline",
                        base.toString(),
                        "--store",
                        store.toString());
        assertThat(o.code()).as(o.err() + o.out()).isZero();
        assertThat(o.out()).contains("Same").contains("UNSAFE FLIPS  0").contains("only read");
    }

    @Test
    void anUnsafeFlipExitsOneUnlessTheFailureIsMovedAndTheFormatsAllWork() throws Exception {
        Path base = base();
        Path store = store(base);
        Path nobody = candidate("nobody", "human_prompt \"Send this reply? (yes/no)\" -> ok\n", "");
        String[] common = {
            nobody.toString(), "--baseline", base.toString(), "--store", store.toString()
        };
        assertThat(regress(common).code()).isEqualTo(1);
        List<String> none = new java.util.ArrayList<>(List.of(common));
        none.addAll(List.of("--fail-on", "none"));
        assertThat(regress(none.toArray(new String[0])).code()).isZero();
        List<String> json = new java.util.ArrayList<>(List.of(common));
        json.addAll(List.of("--format", "json", "--fail-on", "none"));
        Out j = regress(json.toArray(new String[0]));
        assertThat(new ObjectMapper().readTree(j.out()).get("counts").get("unsafe").asInt())
                .isEqualTo(2);
        for (String f : List.of("md", "junit")) {
            List<String> a = new java.util.ArrayList<>(List.of(common));
            a.addAll(List.of("--format", f, "--fail-on", "none"));
            assertThat(regress(a.toArray(new String[0])).out()).isNotBlank();
        }
        // flip-any also fails on a changed route that is not unsafe
        Path flipped =
                candidate("flip", "intent.kind == \"order_status\"", "intent.kind == \"refund\"");
        assertThat(
                        regress(
                                        flipped.toString(),
                                        "--baseline",
                                        base.toString(),
                                        "--store",
                                        store.toString(),
                                        "--fail-on",
                                        "flip-any")
                                .code())
                .isEqualTo(1);
    }

    @Test
    void theHtmlReportIsWrittenAsOneFile() throws Exception {
        Path base = base();
        Path store = store(base);
        Path html = dir.resolve("report.html");
        Out o =
                regress(
                        base.toString(),
                        "--baseline",
                        base.toString(),
                        "--store",
                        store.toString(),
                        "--report",
                        html.toString());
        assertThat(o.code()).isZero();
        assertThat(Files.readString(html)).startsWith("<!doctype html>").doesNotContain("https://");
    }

    @Test
    void whatCannotRunExitsTwoAndUsageErrorsExitOne() throws Exception {
        Path base = base();
        assertThat(regress(base.toString(), "--store", dir.resolve("nowhere").toString()).code())
                .isEqualTo(2);
        assertThat(regress(dir.resolve("none.loom").toString()).code()).isEqualTo(2);
        Path store = Files.createDirectories(dir.resolve("empty"));
        Out empty =
                regress(
                        base.toString(),
                        "--baseline",
                        base.toString(),
                        "--store",
                        store.toString());
        assertThat(empty.code()).isEqualTo(2);
        assertThat(empty.err()).contains("no past run");
        assertThat(regress(base.toString(), "--format", "xml").code()).isEqualTo(1);
        assertThat(regress(base.toString(), "--fail-on", "x").code()).isEqualTo(1);
        assertThat(
                        regress(base.toString(), "--sample", "bogus", "--store", store.toString())
                                .code())
                .isEqualTo(2);
        Path broken = dir.resolve("broken.loom");
        Files.writeString(broken, "workflow {");
        Out b = regress(broken.toString(), "--store", dir.resolve("store").toString());
        assertThat(b.code()).isEqualTo(2);
    }

    @Test
    void whatSpendsMoneyNeedsACapAndSaysSoBeforeAnythingRuns() throws Exception {
        Path base = base();
        Path store = store(base);
        Out live = regress(base.toString(), "--models", "live", "--store", store.toString());
        assertThat(live.code()).isEqualTo(1);
        assertThat(live.err()).contains("costs money").contains("--max-cost");
        Path rubric = Files.writeString(dir.resolve("rubric.md"), "Tone matters.");
        Out judge = regress(base.toString(), "--judge", "rubric:" + rubric, "--judge-model", "m", "--store", store.toString());
        assertThat(judge.code()).isEqualTo(1);
        assertThat(judge.err()).contains("costs money");
        Out noPrices = regress(base.toString(), "--models", "live", "--max-cost", "0.10", "--store", store.toString());
        assertThat(noPrices.code()).isEqualTo(1);
        assertThat(noPrices.err()).contains("--prices");
        assertThat(regress(base.toString(), "--fail-on", "cost-up:x", "--store", store.toString()).code()).isEqualTo(1);
    }
}
