package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.loop.Approval;
import io.github.llm4j.loom.loop.Candidates;
import io.github.llm4j.loom.loop.Capture;
import io.github.llm4j.loom.loop.Consent;
import io.github.llm4j.loom.loop.Policy;
import io.github.llm4j.loom.loop.RunRecord;
import io.github.llm4j.loom.loop.StoreReader;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave capture}: a real run becomes a test case. Reads the run's journal (never writes to
 * it), takes out personal data and secrets, checks that the case passes against the script that
 * ran, and writes it as {@code observed} until a person approves it.
 *
 * <p>Exit codes: 0 done, 1 usage, 2 could not (refused, or the case would not hold), 3 a different
 * file is already there.
 */
@Command(
        name = "capture",
        description =
                "Turns a real run into a test case with the recorded answers scripted in, so it reproduces what production did with no model. Personal data and secrets are masked first; the case is 'observed' until a person approves it.")
final class CaptureCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<run folder or id>",
            description = "The run to capture (a run folder, or an id in --store).")
    String run;

    @Option(names = "--store", description = "Where past runs are kept (default: .loom-triggers).")
    File store;

    @Option(
            names = "--to",
            description = "The folder for the case (default: tests/captured beside the script).")
    File to;

    @Option(
            names = "--name",
            description =
                    "The case's file name (default: from the workflow, the run and the date).")
    String name;

    @Option(
            names = "--as",
            description =
                    "test (default): a test case; golden: an entry of the evaluation dataset; both.",
            defaultValue = "test")
    String as;

    @Option(
            names = "--expect",
            description =
                    "observed: fill the expectations from what happened (default); none: only how it ends.",
            defaultValue = "observed")
    String expect;

    @Option(
            names = "--mask",
            description =
                    "pii (default): mask personal data and secrets; none: leave personal data (secrets are always taken out).",
            defaultValue = "pii")
    String mask;

    @Option(
            names = "--mask-term",
            description = "A name or word to mask that no pattern can find (repeatable).")
    List<String> maskTerm = new ArrayList<>();

    @Option(
            names = "--mask-keep",
            description =
                    "An input or agent whose values are kept as they are (you take the responsibility).")
    List<String> maskKeep = new ArrayList<>();

    @Option(names = "--note", description = "Why this run was captured.")
    String note;

    @Option(names = "--tag", description = "A tag for the case (repeatable).")
    List<String> tag = new ArrayList<>();

    @Option(names = "--dry-run", description = "Print the case, write nothing.")
    boolean dryRun;

    @Option(names = "--yes", description = "Agree to what capture does, once for this repository.")
    boolean yes;

    @Option(names = "--list", description = "List the runs worth capturing, and why.")
    boolean list;

    @Option(
            names = "--since",
            description = "With --list or --select: only runs this recent (14d, 12h, or a date).")
    String since;

    @Option(
            names = "--select",
            description =
                    "Capture the newest runs of a class: failed, diverted, unsure, overridden.")
    String select;

    @Option(
            names = "--limit",
            description = "With --select: how many (default 5).",
            defaultValue = "5")
    int limit;

    @Option(names = "--approve", description = "Approve a captured case file after reading it.")
    File approve;

    @Option(names = "--retire", description = "Retire a captured case file (no longer applicable).")
    File retire;

    @Option(
            names = "--by",
            description = "Who approves or retires (default: git user.email, else the OS user).")
    String by;

    @Option(names = "--why", description = "Why, for the record.")
    String why;

    @Option(
            names = "--amend",
            description =
                    "With --approve: change what the case expects, final=... or result=..., in the same step.")
    List<String> amend = new ArrayList<>();

    @Override
    public Integer call() {
        return capture(this, WeaveEnv.system());
    }

    static int capture(CaptureCommand c, WeaveEnv env) {
        try {
            if (c.approve != null || c.retire != null) return decide(c, env);
            if (c.list) return list(c, env);
            if (c.run == null && c.select == null) {
                env.err()
                        .println(
                                "Error: say which run (a folder or an id), or --list, or --select.");
                return 1;
            }
            if (!Set.of("test", "golden", "both").contains(c.as)) {
                env.err().println("Error: --as is test, golden or both.");
                return 1;
            }
            if (!Set.of("observed", "none").contains(c.expect)) {
                env.err().println("Error: --expect is observed or none.");
                return 1;
            }
            if (!Set.of("pii", "none").contains(c.mask)) {
                env.err().println("Error: --mask is pii or none.");
                return 1;
            }
            List<RunRecord> runs = new ArrayList<>();
            Path store = storeOf(c);
            if (c.select != null) {
                List<Candidates.Candidate> all =
                        Candidates.list(
                                StoreReader.list(store), Candidates.since(c.since, Instant.now()));
                for (Candidates.Candidate k : Candidates.select(all, c.select, c.limit))
                    runs.add(k.run());
                if (runs.isEmpty()) {
                    env.err().println("Error: no " + c.select + " runs in " + store + ".");
                    return 2;
                }
            } else runs.add(StoreReader.find(c.run, store));
            int worst = 0;
            for (RunRecord r : runs) worst = Math.max(worst, one(c, env, r));
            return worst;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            env.err().println("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 2;
        }
    }

    private static Path storeOf(CaptureCommand c) {
        return (c.store != null ? c.store.toPath() : Path.of(".loom-triggers")).toAbsolutePath();
    }

    private static int one(CaptureCommand c, WeaveEnv env, RunRecord r) throws Exception {
        Policy policy = Policy.load(r.script());
        List<String> refused = policy.violations(r, Instant.now());
        if (!refused.isEmpty()) {
            env.err()
                    .println(
                            "Error: "
                                    + r.id()
                                    + " may not be captured: "
                                    + String.join("; ", refused));
            return 2;
        }
        if (!c.dryRun && !Consent.given(r.script())) {
            env.out().println(Consent.NOTICE);
            boolean agreed = c.yes;
            if (!agreed) {
                String a;
                try {
                    a = env.human().promptHuman("Agree, for this repository? (yes/no)");
                } catch (RuntimeException e) {
                    a = "no";
                }
                agreed = a != null && a.trim().equalsIgnoreCase("yes");
            }
            if (!agreed) {
                env.err()
                        .println(
                                "Error: nothing captured; capture needs the agreement above (--yes records it).");
                return 2;
            }
            Consent.record(r.script(), Identity.of(c.by, Consent.root(r.script())));
        }
        Set<String> deny = new LinkedHashSet<>(policy.denyFields());
        Capture.Options options =
                new Capture.Options(
                        c.to == null ? null : c.to.toPath().toAbsolutePath(),
                        c.expect.equals("none") ? Capture.Expect.NONE : Capture.Expect.OBSERVED,
                        new LinkedHashSet<>(c.maskTerm),
                        new LinkedHashSet<>(c.maskKeep),
                        deny,
                        c.mask.equals("pii"),
                        c.note,
                        c.tag,
                        c.name);
        if (c.as.equals("golden") || c.as.equals("both")) {
            io.github.llm4j.loom.loop.CaptureGolden.Entry g =
                    io.github.llm4j.loom.loop.CaptureGolden.capture(r, options, !c.dryRun);
            if (c.dryRun) env.out().print(g.entry());
            else
                env.out()
                        .println(
                                (g.already()
                                                ? "Already in the dataset: "
                                                : "Added to the dataset: ")
                                        + r.id()
                                        + " in "
                                        + g.file()
                                        + (g.already()
                                                ? ""
                                                : ". Write its rubric (what a good answer does) before weave eval uses it."));
            if (c.as.equals("golden")) return 0;
        }
        Capture.Captured k = Capture.capture(r, options, !c.dryRun);
        if (c.dryRun) {
            env.out().print(k.text());
            return 0;
        }
        if (k.conflict()) {
            env.err()
                    .println(
                            "Error: "
                                    + k.file()
                                    + " already exists and is not what this capture would write (has it been edited?). Nothing was written; delete it or choose --name.");
            return 3;
        }
        env.out()
                .println(
                        (k.unchanged() ? "Already captured: " : "Captured ")
                                + r.id()
                                + " as "
                                + k.file()
                                + (k.masked().isEmpty()
                                        ? ""
                                        : " (masked: " + String.join(", ", k.masked()) + ")"));
        env.out()
                .println(
                        "It is 'observed' (what happened, which may have been wrong). Read it, then: weave capture --approve "
                                + k.file());
        return 0;
    }

    private static int list(CaptureCommand c, WeaveEnv env) throws Exception {
        Path store = storeOf(c);
        List<Candidates.Candidate> all =
                Candidates.list(StoreReader.list(store), Candidates.since(c.since, Instant.now()));
        if (all.isEmpty()) {
            env.out()
                    .println(
                            "No run in "
                                    + store
                                    + " failed, was diverted, was unsure or was stepped in on.");
            return 0;
        }
        env.out().println(all.size() + " run(s) worth capturing in " + store + ":");
        for (Candidates.Candidate k : all)
            env.out()
                    .println(
                            "  "
                                    + k.run().id()
                                    + "  "
                                    + k.status()
                                    + "  "
                                    + String.join(", ", k.classes()));
        return 0;
    }

    private static int decide(CaptureCommand c, WeaveEnv env) throws Exception {
        Path file = (c.approve != null ? c.approve : c.retire).toPath();
        String who = Identity.of(c.by, file.toAbsolutePath().getParent());
        if (c.approve != null) {
            Map<String, String> amend = new LinkedHashMap<>();
            for (String a : c.amend) {
                int eq = a.indexOf('=');
                if (eq < 1) {
                    env.err().println("Error: --amend is key=value (final=... or result=...).");
                    return 1;
                }
                amend.put(a.substring(0, eq), a.substring(eq + 1));
            }
            Approval.Changed ch = Approval.approve(file, who, c.why, amend);
            env.out()
                    .println(
                            "Approved "
                                    + file
                                    + " (by "
                                    + ch.by()
                                    + "). It now fails a gate when the script stops doing this.");
        } else {
            Approval.retire(file, who, c.why);
            env.out().println("Retired " + file + ".");
        }
        return 0;
    }
}
