package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LadderDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.expr.V;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.ladder.HistoryBuilder;
import io.github.llm4j.loom.ladder.LCase;
import io.github.llm4j.loom.ladder.LadderDescriber;
import io.github.llm4j.loom.ladder.LadderEngine;
import io.github.llm4j.loom.ladder.LadderPolicy;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave ladder describe|test}: a ladder read back in plain sentences, and tried on a
 * recorded history. Neither runs an agent, asks a model or writes anything.
 */
@Command(
        name = "ladder",
        description =
                "Describes a ladder in plain sentences, or tries it on a recorded history.%n  describe | test",
        subcommands = {LadderCliCommand.Describe.class, LadderCliCommand.Try.class})
final class LadderCliCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Choose a command: describe or test (weave ladder --help).");
        return 2;
    }

    @Command(
            name = "describe",
            description =
                    "Says in plain sentences what a ladder requires to climb, what makes it slip and who is asked: generated from the parsed ladder, so what is read is what is enforced.")
    static class Describe implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script.")
        File script;

        @Parameters(index = "1", arity = "0..1", description = "Only this ladder.")
        String name;

        @Override
        public Integer call() {
            return describe(script, name, WeaveEnv.system());
        }
    }

    static int describe(File script, String name, WeaveEnv env) {
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(script.getAbsolutePath());
        } catch (Exception e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        // a decision named here is read as a ladder too: its trust block as the ladder written from
        // it, or the reason it stays on the original engine
        DecisionDef decision =
                name == null
                        ? null
                        : loaded.getDecisions().stream()
                                .filter(d -> d.getName().equals(name))
                                .findFirst()
                                .orElse(null);
        if (decision != null && decision.isLadderFromTrust()) {
            LadderDef written =
                    loaded.getLadders().stream()
                            .filter(l -> l.getName().equals(decision.getLadder()))
                            .findFirst()
                            .orElseThrow();
            env.out().println("The trust block of " + decision.getName() + ", read as a ladder:\n");
            env.out().println(LadderDescriber.describe(written, decision));
            return 0;
        }
        if (decision != null && decision.getLadder() == null) {
            env.out()
                    .println(
                            decision.getName()
                                    + " runs on the original engine, not on a ladder: "
                                    + io.github.llm4j.loom.ladder.TrustDesugar.of(
                                                    decision, java.util.Set.of())
                                            .unsupported()
                                    + ".");
            return 0;
        }
        List<LadderDef> declared =
                loaded.getLadders().stream().filter(l -> !l.isSynthetic()).toList();
        List<LadderDef> ladders =
                declared.stream().filter(l -> name == null || l.getName().equals(name)).toList();
        if (ladders.isEmpty()) {
            env.err()
                    .println(
                            "Error: "
                                    + (name == null
                                            ? script + " declares no ladder."
                                            : "no ladder named "
                                                    + name
                                                    + ". Ladders: "
                                                    + declared.stream()
                                                            .map(LadderDef::getName)
                                                            .collect(Collectors.joining(", "))
                                                    + "."));
            return 2;
        }
        for (LadderDef l : ladders) {
            List<DecisionDef> users =
                    loaded.getDecisions().stream()
                            .filter(d -> l.getName().equals(d.getLadder()))
                            .toList();
            if (users.isEmpty()) env.out().println(LadderDescriber.describe(l, null));
            for (DecisionDef d : users) env.out().println(LadderDescriber.describe(l, d));
        }
        return 0;
    }

    @Command(
            name = "test",
            description =
                    "Tries a ladder on the history in a store: for each scope the rung it would hold and each condition's value. Writes nothing.")
    static class Try implements Callable<Integer> {
        @Parameters(index = "0", description = "The .loom script.")
        File script;

        @Parameters(index = "1", description = "The decision.")
        String decision;

        @Option(
                names = "--store",
                required = true,
                description = "The run store whose recorded history is used.")
        File store;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return test(script, decision, store.toPath(), env.clock(), env.out(), env.err());
        }
    }

    static int test(
            File script,
            String decision,
            Path store,
            Clock clock,
            PrintStream out,
            PrintStream err) {
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(script.getAbsolutePath());
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            return 2;
        }
        DecisionDef def =
                loaded.getDecisions().stream()
                        .filter(d -> d.getName().equals(decision))
                        .findFirst()
                        .orElse(null);
        if (def == null || def.getLadder() == null) {
            err.println(
                    "Error: "
                            + script
                            + " has no decision named "
                            + decision
                            + " that binds a ladder.");
            return 2;
        }
        Ledger ledger = AutonomySupport.ledger(store);
        LadderPolicy policy = LadderPolicy.of(loaded, def);
        LadderEngine engine = new LadderEngine(policy, ledger, clock);
        List<LCase> cases = engine.cases();
        out.println(decision + "   ladder " + policy.ladderName() + "   (nothing is written)");
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        cases.stream().filter(c -> !c.superseded()).forEach(c -> scopes.add(c.scope()));
        if (scopes.isEmpty()) out.println("  no cases in " + store);
        long now = clock.millis();
        for (String scope : scopes) {
            int epoch =
                    cases.stream()
                            .filter(c -> !c.superseded() && c.scope().equals(scope))
                            .mapToInt(LCase::epoch)
                            .max()
                            .orElse(1);
            V.Cs history = HistoryBuilder.history(cases, scope, epoch);
            int rung = 0;
            while (rung + 1 <= policy.ceiling()
                    && rung + 1 < policy.rungs().size()
                    && policy.climbs(rung + 1, history, now)
                    && policy.slip(rung + 1, history, now).isEmpty()) rung++;
            while (rung > 0 && policy.slip(rung, history, now).isPresent())
                rung = policy.slip(rung, history, now).get();
            out.println(
                    "  scope "
                            + scope
                            + "   would hold rung "
                            + policy.nameOf(rung)
                            + "   ("
                            + history.cases().size()
                            + " answered cases in epoch "
                            + epoch
                            + ")");
            int next = rung + 1;
            if (next < policy.rungs().size() && next <= policy.ceiling()) {
                out.println("    towards " + policy.nameOf(next) + ":");
                for (LadderPolicy.Term t : policy.explainClimb(next, history, now)) {
                    out.println(
                            "      - "
                                    + t.text()
                                    + "   ["
                                    + t.actual()
                                    + (t.needed().isEmpty() ? "" : " vs " + t.needed())
                                    + "]   "
                                    + t.met().map(m -> m ? "met" : "not met").orElse("unknown"));
                }
            }
            for (LadderPolicy.Term t : policy.explainSlip(rung, history, now)) {
                out.println(
                        "    slip if "
                                + t.text()
                                + "   ["
                                + t.actual()
                                + (t.needed().isEmpty() ? "" : " vs " + t.needed())
                                + "]   "
                                + t.met().map(m -> m ? "HOLDS" : "no").orElse("unknown"));
            }
        }
        return 0;
    }
}
