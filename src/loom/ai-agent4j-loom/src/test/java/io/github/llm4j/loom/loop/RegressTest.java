package io.github.llm4j.loom.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R18, R19, R20, R22, R23, R26, R28, R29, R30 of spec 14: a candidate against real history. */
class RegressTest {

    @TempDir Path dir;

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * A store: {@code orders} order-status runs (a person answers), {@code refunds}, {@code
     * others}.
     */
    private Path store(Path script, int orders, int refunds, int others) throws Exception {
        Path store = dir.resolve("store");
        int n = 0;
        for (int i = 0; i < orders; i++) one(store, script, "o" + n++, "order_status", true);
        for (int i = 0; i < refunds; i++) one(store, script, "r" + n++, "refund", false);
        for (int i = 0; i < others; i++) one(store, script, "x" + n++, "other", false);
        return store;
    }

    private void one(Path store, Path script, String id, String kind, boolean person)
            throws Exception {
        var body = M.createObjectNode();
        body.putObject("input").put("message", "message " + id);
        if (person) body.putArray("humans").add("yes");
        var models = body.putObject("models");
        models.putObject("Intent").put("kind", kind).put("urgent", "no");
        models.put("Replier", "On its way.");
        models.put("Escalator", "A person should look.");
        Runs.run(store, id, script, body, Map.of("message", "message " + id));
    }

    private Regress.Report regress(
            Path candidate, Path baseline, Path store, int limit, Sampler.Strategy strategy)
            throws Exception {
        Baselines b =
                Baselines.of(candidate, baseline, null, dir.resolve("tmp" + System.nanoTime()));
        return Regress.run(
                new Regress.Request(
                        candidate, b, store, null, limit, strategy, 7, "none", null, null, 30));
    }

    private Path variant(String name, String from, String to) throws Exception {
        Path proj = dir.resolve(name);
        return Runs.project(proj, Runs.TRIAGE.replace(from, to));
    }

    @Test
    void theSameScriptChangesNothing() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 3, 2, 1);
        Path same = Runs.project(dir.resolve("same"), Runs.TRIAGE);
        Regress.Report r = regress(same, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(r.comparisons()).hasSize(6);
        assertThat(r.count(Regress.Verdict.SAME)).isEqualTo(6);
        assertThat(r.unsafe()).isEmpty();
    }

    @Test
    void aFlippedConditionChangesTheRouteOfExactlyTheRunsItTouches() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 3, 2, 1);
        Path flipped =
                variant("flip", "intent.kind == \"order_status\"", "intent.kind == \"refund\"");
        Regress.Report r = regress(flipped, base, store, 200, Sampler.Strategy.STRATIFIED);
        // order_status runs (3) and refund runs (2) swap branches; "other" stays on the else branch
        long changed =
                r.count(Regress.Verdict.CHANGED_ROUTE) + r.count(Regress.Verdict.NEEDS_MODEL);
        assertThat(changed).isGreaterThanOrEqualTo(4);
        assertThat(r.comparisons().stream().filter(c -> c.run().startsWith("x")))
                .allMatch(c -> c.verdict() == Regress.Verdict.SAME);
    }

    @Test
    void takingThePersonOutOfThePathIsAnUnsafeFlipAndIsListed() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 3, 0, 0);
        Path nobody = variant("nobody", "human_prompt \"Send this reply? (yes/no)\" -> ok\n", "");
        Regress.Report r = regress(nobody, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(r.unsafe()).hasSize(3);
        assertThat(r.unsafe().get(0).unsafe().get(0)).contains("a person is no longer asked");
    }

    @Test
    void aChangedPromptIsCountedAsNeedingAModelAndNeverGuessed() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 3, 2, 0);
        Path reworded =
                variant("reworded", "Reply to: {message}", "Please reply politely to: {message}");
        Regress.Report r = regress(reworded, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(r.count(Regress.Verdict.NEEDS_MODEL)).isEqualTo(3);
        assertThat(r.count(Regress.Verdict.SAME)).isEqualTo(2);
        String text = RegressReport.text(r, false, null);
        assertThat(text).contains("Needs a model").contains("never guessed");
    }

    @Test
    void theReportSaysWhatTheSampleIsAndNeverMoreThanItSupports() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 20, 10, 10);
        Path flipped =
                variant("flip", "intent.kind == \"order_status\"", "intent.kind == \"refund\"");
        Regress.Report strat = regress(flipped, base, store, 12, Sampler.Strategy.STRATIFIED);
        String text = RegressReport.text(strat, false, null);
        assertThat(text)
                .contains("12 of 40 eligible runs (stratified, seed 7)")
                .contains("not random")
                .contains("about the runs sampled, not about production")
                .doesNotContain("95 %");
        Regress.Report random = regress(flipped, base, store, 12, Sampler.Strategy.RANDOM);
        assertThat(RegressReport.text(random, false, null))
                .contains("95 % interval")
                .doesNotContain("not random");
        // a sample that is everything claims no interval
        Regress.Report all = regress(flipped, base, store, 100, Sampler.Strategy.RANDOM);
        assertThat(RegressReport.text(all, false, null))
                .doesNotContain("95 %")
                .doesNotContain("not random");
    }

    @Test
    void theStoreIsOnlyReadAndNoTextIsInTheReportUnlessAskedAndThenMasked() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2, 1, 0);
        String before = StoreReader.fingerprint(store);
        Path flipped =
                variant("flip", "intent.kind == \"order_status\"", "intent.kind == \"refund\"");
        Regress.Report r = regress(flipped, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(StoreReader.fingerprint(store)).isEqualTo(before);
        assertThat(r.storeUntouched()).isTrue();
        String json = RegressReport.json(r, false, new Masker());
        assertThat(json).doesNotContain("On its way").doesNotContain("message o");
        assertThat(M.readTree(json).get("version").asInt()).isEqualTo(1);
    }

    @Test
    void aRunOfAnotherVersionOrOfAnotherWorkflowIsLeftOutAndCounted() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2, 0, 0);
        Path other = Runs.project(dir.resolve("other"), Runs.TRIAGE + "\n// a different version\n");
        Path otherStore = store;
        one(otherStore, other, "old", "refund", false);
        Regress.Report r = regress(base, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(r.excluded())
                .anyMatch(e -> e.run().equals("old") && e.why().contains("another version"));
        assertThat(RegressReport.text(r, false, null)).contains("Left out: 1 run(s)");
    }

    @Test
    void theRecordedVersionIsFoundInGitHistoryWhenNoBaselineIsGiven() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        git(repo, "init", "-q");
        Path script = Runs.project(repo, Runs.TRIAGE);
        git(repo, "add", ".");
        git(
                repo,
                "-c",
                "user.email=t@example.com",
                "-c",
                "user.name=t",
                "commit",
                "-q",
                "-m",
                "first");
        Path store = store(script, 3, 1, 0);
        // the candidate is the same file, edited and not committed
        Files.writeString(
                script,
                Runs.TRIAGE.replace(
                        "intent.kind == \"order_status\"", "intent.kind == \"refund\""));
        Baselines b = Baselines.of(script, null, null, dir.resolve("tmpg"));
        Regress.Report r =
                Regress.run(
                        new Regress.Request(
                                script,
                                b,
                                store,
                                null,
                                200,
                                Sampler.Strategy.STRATIFIED,
                                7,
                                "none",
                                null,
                                null,
                                30));
        assertThat(r.excluded()).isEmpty();
        assertThat(r.count(Regress.Verdict.SAME)).isLessThan(4);
        Baselines atRef = Baselines.of(script, null, "HEAD", dir.resolve("tmpr"));
        Regress.Report r2 =
                Regress.run(
                        new Regress.Request(
                                script,
                                atRef,
                                store,
                                null,
                                200,
                                Sampler.Strategy.STRATIFIED,
                                7,
                                "none",
                                null,
                                null,
                                30));
        assertThat(r2.comparisons()).hasSize(4);
    }

    @Test
    void theHtmlIsOneFileWithNothingRemoteAndEverythingEscaped() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2, 0, 0);
        Path nobody = variant("nobody", "human_prompt \"Send this reply? (yes/no)\" -> ok\n", "");
        Regress.Report r = regress(nobody, base, store, 200, Sampler.Strategy.STRATIFIED);
        String html = RegressReport.html(r);
        assertThat(html)
                .startsWith("<!doctype html>")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("<script");
        assertThat(RegressReport.esc("<img src=x onerror=alert(1)>")).doesNotContain("<");
        assertThat(RegressReport.junit(r)).contains("<failure").contains("testsuite");
        assertThat(RegressReport.markdown(r)).contains("Unsafe flips: 2");
    }

    @Test
    void aCandidateThatAddsAnEffectToThePathIsAnUnsafeFlipNamingTheTool() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 2, 0, 0);
        String withHook =
                Runs.TRIAGE.replace(
                        "agent Replier { model: \"gemini-2.5-flash\"  system: \"Answer the customer.\" }",
                        "tool Hook { use: webhook  url: env.HOOK_URL }\nagent Replier { model: \"gemini-2.5-flash\"  system: \"Answer the customer.\" tools: [Hook] }");
        Path candidate = Runs.project(dir.resolve("hook"), withHook);
        Regress.Report r = regress(candidate, base, store, 200, Sampler.Strategy.STRATIFIED);
        assertThat(r.unsafe()).isNotEmpty();
        assertThat(r.unsafe().get(0).unsafe().get(0)).contains("NEW EFFECT").contains("Hook");
    }

    @Test
    void aCandidateThatLoopsIsStoppedAndReportedNotHung() throws Exception {
        Path base = Runs.project(dir.resolve("proj"), Runs.TRIAGE);
        Path store = store(base, 1, 0, 0);
        String looping =
                Runs.TRIAGE.replace(
                        "intentclassify Intent on message -> intent",
                        "intentclassify Intent on message -> intent\n    set n = \"a\"\n    loop until (n == \"never\") max 2000000000 {\n        set n = \"a\"\n    }");
        Path candidate = Runs.project(dir.resolve("loops"), looping);
        Baselines b = Baselines.of(candidate, base, null, dir.resolve("tmpl"));
        long t0 = System.nanoTime();
        Regress.Report r =
                Regress.run(
                        new Regress.Request(
                                candidate,
                                b,
                                store,
                                null,
                                200,
                                Sampler.Strategy.STRATIFIED,
                                7,
                                "none",
                                null,
                                null,
                                2));
        assertThat((System.nanoTime() - t0) / 1_000_000_000).isLessThan(20);
        assertThat(r.count(Regress.Verdict.DID_NOT_FINISH)).isEqualTo(1);
        assertThat(r.unsafe()).hasSize(1);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p =
                new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).as(String.join(" ", cmd)).isZero();
    }
}
