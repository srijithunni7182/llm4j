package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.ast.DecisionDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.autonomy.AgentIdentity;
import io.github.llm4j.loom.autonomy.AgreementStats;
import io.github.llm4j.loom.autonomy.Case;
import io.github.llm4j.loom.autonomy.Engine;
import io.github.llm4j.loom.autonomy.Ladder;
import io.github.llm4j.loom.autonomy.Ledger;
import io.github.llm4j.loom.autonomy.Level;
import io.github.llm4j.loom.autonomy.LevelState;
import io.github.llm4j.loom.autonomy.LevelStore;
import io.github.llm4j.loom.autonomy.Names;
import io.github.llm4j.loom.autonomy.Rec;
import io.github.llm4j.loom.execution.LoomLoader;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave autonomy status|history|promote|demote|approve|reject|freeze|unfreeze|outcome}: where every ladder stands, and the ways a person
 * moves one. They read and change the same ledger and level store the runs use (the {@code autonomy} directory of the run store), through
 * the same {@link Engine}, and every change is written to {@code <store>/autonomy/audit.jsonl} with the rule that fired and the figures it used.
 */
@Command(name = "autonomy", description = "Where each decision's ladder stands, and the ways a person moves it.%n"
        + "  status | history | promote | demote | approve | reject | freeze | unfreeze | outcome",
        subcommands = {AutonomyCommands.Status.class, AutonomyCommands.History.class, AutonomyCommands.Promote.class, AutonomyCommands.Demote.class,
                AutonomyCommands.Approve.class, AutonomyCommands.Reject.class, AutonomyCommands.Freeze.class, AutonomyCommands.Unfreeze.class,
                AutonomyCommands.Outcome.class})
final class AutonomyCommands implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Choose a command: status, history, promote, demote, approve, reject, freeze, unfreeze or outcome (weave autonomy --help).");
        return 2;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String operator() {
        String user = System.getProperty("user.name");
        return "operator:" + (user == null ? "unknown" : user);
    }

    /** The decision's rules: from the script it was last run from, or the one given. */
    private static Optional<DecisionDef> definition(Path store, String decision, File script, PrintStream err) {
        Path file = script != null ? script.toPath() : AutonomySupport.scriptOf(store, decision).orElse(null);
        if (file == null || !Files.exists(file)) {
            err.println("Error: don't know which script declares " + decision + " (it was never run from this store, or the script moved). Pass --script <file>.");
            return Optional.empty();
        }
        try {
            LoomScript loaded = new LoomLoader().load(file.toAbsolutePath().toString());
            Optional<DecisionDef> def = loaded.getDecisions().stream().filter(d -> d.getName().equals(decision)).findFirst();
            if (def.isEmpty()) err.println("Error: " + file + " has no decision named " + decision + ".");
            return def;
        } catch (Exception e) {
            err.println("Error: could not read " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static void audit(Path store, String event, Map<String, Object> data) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", event);
        record.put("time", Instant.now().toString());
        record.put("by", operator());
        record.putAll(data);
        try {
            Files.createDirectories(AutonomySupport.dir(store));
            Files.writeString(AutonomySupport.dir(store).resolve("audit.jsonl"), JSON.writeValueAsString(record) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String shown(String scope) {
        return scope;
    }

    // ---- status ---------------------------------------------------------------------------------------------------------------

    @Command(name = "status", description = "Per decision and scope: the level, the evidence, agreement and its lower bound, what is missing for the next step, stale cases, the last change.")
    static class Status implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store (the directory that holds the triggers and the autonomy files).")
        File store;

        @Option(names = "--decision", description = "Only this decision.")
        String decision;

        @Option(names = "--script", description = "The script that declares the decision (default: the one it was last run from).")
        File script;

        @Option(names = "--json", description = "Print JSON.")
        boolean json;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return status(store.toPath(), decision, script, json, env.clock(), env.out(), env.err());
        }
    }

    static int status(Path store, String only, File script, boolean json, Clock clock, PrintStream out, PrintStream err) {
        List<String> decisions = only != null ? List.of(Names.check(only)) : AutonomySupport.decisions(store);
        if (decisions.isEmpty()) {
            err.println("Error: no decision has run from " + store + " yet (there is no autonomy directory).");
            return 2;
        }
        Ledger ledger = AutonomySupport.ledger(store);
        LevelStore levels = AutonomySupport.levels(store);
        List<Map<String, Object>> all = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (String name : decisions) {
            Optional<DecisionDef> def = definition(store, name, script, err);
            if (def.isEmpty()) return 2;
            Map<String, Object> d = describe(def.get(), ledger, levels, clock, text);
            all.add(d);
        }
        if (json) {
            try {
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            out.print(text);
        }
        return 0;
    }

    private static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private static Map<String, Object> describe(DecisionDef def, Ledger ledger, LevelStore levels, Clock clock, StringBuilder text) {
        Engine engine = new Engine(def, ledger, levels, clock);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("decision", def.getName());
        boolean frozen = levels.frozen(def.getName());
        d.put("frozen", frozen);
        text.append(def.getName()).append(frozen ? "   FROZEN (act is stopped: " + levels.freeze(def.getName()).or(() -> levels.freeze("*")).map(LevelStore.Freeze::reason).orElse("") + ")" : "").append('\n');
        int torn = ledger.unreadable(def.getName());
        if (torn > 0) {
            d.put("unreadableLedgerLines", torn);
            text.append("  WARNING: ").append(torn).append(" ledger line(s) could not be read (a write that was cut off); the next append repairs it.\n");
        }
        List<Map<String, Object>> scopes = new ArrayList<>();
        List<Case> cases = ledger.cases(def.getName());
        for (Map.Entry<String, LevelState> e : levels.scopes(def.getName()).entrySet()) {
            String scope = e.getKey();
            LevelState state = e.getValue();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("scope", scope);
            s.put("level", state.level().word());
            s.put("effectiveLevel", engine.effective(state).word());
            s.put("epoch", state.epoch());
            s.put("agent", AgentIdentity.shorten(state.identity()));
            boolean unearned = engine.unearned(scope, state);
            s.put("forced", unearned);
            AgreementStats.Figures f = engine.ladder().figures(scope, state.epoch());
            s.put("cases", f.cases());
            s.put("agreement", f.rate());
            s.put("lowerBound", f.lowerBound());
            s.put("dangerousRate", f.dangerousRate());
            s.put("coverage", f.coverage());
            s.put("malformed", f.malformed());
            text.append("  scope ").append(shown(scope)).append("   level ").append(state.level().word());
            if (!engine.effective(state).equals(state.level())) text.append(" (running at ").append(engine.effective(state).word()).append(")");
            if (unearned) text.append("   FORCED: set by hand, not yet supported by the evidence");
            text.append("   (epoch ").append(state.epoch()).append(", agent ").append(AgentIdentity.shorten(state.identity())).append(")\n");
            text.append("    evidence: ").append(f.cases()).append(" blind cases of the latest ").append(def.getWindow()).append(";  agreement ").append(pct(f.rate()))
                    .append(" (lower bound ").append(pct(f.lowerBound())).append(");  dangerous mistakes ").append(pct(f.dangerousRate()))
                    .append(";  coverage ").append(pct(f.coverage())).append(";  unusable proposals ").append(f.malformed()).append('\n');
            Ladder.Progress p = engine.ladder().progress(scope, state);
            if (p.next() == null || p.parts().isEmpty()) {
                text.append("    next step: ").append(p.reasonNone()).append('\n');
                s.put("nextStep", p.reasonNone());
            } else {
                List<String> parts = new ArrayList<>();
                p.parts().forEach(part -> parts.add(part.toString()));
                text.append("    towards ").append(p.next().word()).append(p.eligible() ? ": every part is met" : ":").append('\n');
                if (!p.eligible()) for (Ladder.Part part : p.parts()) text.append("      - ").append(part).append('\n');
                s.put("towards", p.next().word());
                s.put("eligible", p.eligible());
                s.put("parts", parts);
            }
            engine.openProposal(scope).ifPresent(prop -> {
                text.append("    open promotion proposal: to ").append(prop.to().word()).append(" on ").append(prop.casesThen()).append(" cases, waiting for ").append(def.getApprover()).append('\n');
                s.put("openProposal", prop.to().word());
            });
            Instant staleBefore = clock.instant().minus(Duration.ofDays(def.getStaleDays()));
            List<String> stale = cases.stream().filter(c -> !c.superseded() && c.scope().equals(scope) && c.verdict() == null && c.at().isBefore(staleBefore)).map(Case::id).toList();
            if (!stale.isEmpty()) {
                text.append("    stale: ").append(stale.size()).append(" case(s) with no verdict after ").append(def.getStaleDays()).append(" days: ").append(String.join(", ", stale.subList(0, Math.min(5, stale.size())))).append('\n');
                s.put("stale", stale);
            }
            Optional<Rec> last = ledger.records(def.getName()).stream().filter(r -> Rec.LEVEL.equals(r.kind()) && scope.equals(r.str("scope"))).reduce((a, b) -> b);
            last.ifPresent(r -> {
                text.append("    last change: ").append(r.at()).append(' ').append(r.str("from") == null ? "(start)" : r.str("from")).append(" -> ").append(r.str("to")).append(" (")
                        .append(r.str("kind")).append(r.flag("forced") ? ", forced" : "").append(", by ").append(r.str("by")).append("): ").append(r.str("reason")).append('\n');
                s.put("lastChange", Map.of("time", r.at().toString(), "to", r.str("to"), "kind", r.str("kind"), "reason", String.valueOf(r.str("reason"))));
            });
            scopes.add(s);
        }
        d.put("scopes", scopes);
        if (scopes.isEmpty()) text.append("  no cases yet\n");
        text.append('\n');
        return d;
    }

    // ---- history --------------------------------------------------------------------------------------------------------------

    @Command(name = "history", description = "Every level change, promotion proposal and freeze, with time, scope, from, to, reason and who approved.")
    static class History implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        File store;

        @Option(names = "--decision", description = "Only this decision.")
        String decision;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return history(store.toPath(), decision, env.out(), env.err());
        }
    }

    static int history(Path store, String only, PrintStream out, PrintStream err) {
        List<String> decisions = only != null ? List.of(Names.check(only)) : AutonomySupport.decisions(store);
        if (decisions.isEmpty()) {
            err.println("Error: no decision has run from " + store + " yet.");
            return 2;
        }
        Ledger ledger = AutonomySupport.ledger(store);
        for (String name : decisions) {
            out.println(name);
            for (Rec r : ledger.records(name)) {
                switch (r.kind()) {
                    case Rec.LEVEL -> out.println("  " + r.at() + "  scope " + r.str("scope") + "  " + (r.str("from") == null ? "(start)" : r.str("from")) + " -> " + r.str("to")
                            + "  [" + r.str("kind") + (r.flag("forced") ? ", forced" : "") + "]  by " + r.str("by") + ": " + r.str("reason"));
                    case Rec.PROMOTION_PROPOSED -> out.println("  " + r.at() + "  scope " + r.str("scope") + "  promotion to " + r.str("to") + " proposed (" + r.str("why") + ")");
                    case Rec.PROMOTION_DECIDED -> out.println("  " + r.at() + "  proposal " + r.str("result") + " by " + r.str("by") + ": " + r.str("reason"));
                    case Rec.FROZEN -> out.println("  " + r.at() + "  " + (r.flag("frozen") ? "FROZEN" : "unfrozen") + " " + r.str("scope") + ": " + r.str("reason"));
                    case Rec.OUTCOME -> out.println("  " + r.at() + "  outcome of " + r.caseId() + ": " + r.str("result") + (r.str("note") == null ? "" : " (" + r.str("note") + ")"));
                    default -> { }
                }
            }
        }
        return 0;
    }

    // ---- promote, demote, approve, reject ---------------------------------------------------------------------------------------

    private abstract static class Mover implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        File store;

        @Parameters(index = "1", description = "The decision.")
        String decision;

        @Option(names = "--scope", required = true, description = "The scope (the value the cases are grouped by).")
        String scope;

        @Option(names = "--reason", required = true, description = "Why (recorded).")
        String reason;

        @Option(names = "--script", description = "The script that declares the decision.")
        File script;

        @Option(names = "--by", description = "Who is doing this (default: the operating system user).")
        String by;
    }

    @Command(name = "promote", description = "Moves a scope up by hand. Beyond the ceiling or beyond what the evidence supports it needs --force, and is then shown as forced.")
    static class Promote extends Mover {
        @Option(names = "--to", description = "The level to move to (default: one step up).")
        String to;

        @Option(names = "--force", description = "Do it even though the ceiling or the evidence says no.")
        boolean force;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return move(store.toPath(), decision, scope, to, true, force, reason, script, by, env);
        }
    }

    @Command(name = "demote", description = "Moves a scope down by hand, at once.")
    static class Demote extends Mover {
        @Option(names = "--to", description = "The level to move to (default: one step down).")
        String to;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return move(store.toPath(), decision, scope, to, false, false, reason, script, by, env);
        }
    }

    static int move(Path store, String decision, String scope, String to, boolean up, boolean force, String reason, File script, String by, WeaveEnv env) {
        Optional<DecisionDef> def = definition(store, Names.check(decision), script, env.err());
        if (def.isEmpty()) return 2;
        Engine engine = new Engine(def.get(), AutonomySupport.ledger(store), AutonomySupport.levels(store), env.clock());
        LevelState state = engine.levels().get(decision, scope).orElse(null);
        if (state == null) {
            env.err().println("Error: no such scope: \"" + scope + "\" (scopes seen: " + String.join(", ", engine.levels().scopes(decision).keySet()) + ")");
            return 2;
        }
        Level target;
        if (to != null) {
            target = Level.of(to);
            if (target == null) {
                env.err().println("Error: --to takes watch, suggest or act.");
                return 2;
            }
        } else {
            int next = state.level().ordinal() + (up ? 1 : -1);
            if (next < 0 || next >= Level.values().length) {
                env.err().println("Error: " + scope + " is already at " + state.level().word() + ".");
                return 2;
            }
            target = Level.values()[next];
        }
        if (up != (target.compareTo(state.level()) > 0) && target != state.level()) {
            env.err().println("Error: " + (up ? "promote" : "demote") + " moves " + (up ? "up" : "down") + ", and " + target.word() + " is " + (up ? "below" : "above") + " " + state.level().word() + ".");
            return 2;
        }
        try {
            Ladder.Progress progress = engine.ladder().progress(scope, state);
            Engine.Change change = engine.set(scope, target, force, reason, by != null ? by : operator());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", decision);
            data.put("scope", scope);
            data.put("from", change.from().word());
            data.put("to", change.to().word());
            data.put("rule", "by hand");
            data.put("reason", reason);
            if (change.forced()) data.put("forced", true);
            data.put("figures", Map.of("cases", progress.figures().cases(), "agreement", progress.figures().rate(), "lowerBound", progress.figures().lowerBound()));
            audit(store, "level_changed", data);
            env.out().println("Moved " + scope + " from " + change.from().word() + " to " + change.to().word() + (change.forced() ? " (FORCED: shown as forced until the evidence supports it)" : "") + ".");
            return 0;
        } catch (IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    @Command(name = "approve", description = "Approves the open promotion proposal of a scope (only the approver the script names may).")
    static class Approve extends Mover {
        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return decide(store.toPath(), decision, scope, true, reason, script, by != null ? by : operator(), env);
        }
    }

    @Command(name = "reject", description = "Rejects the open promotion proposal of a scope; it is not asked again until the evidence has grown.")
    static class Reject extends Mover {
        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return decide(store.toPath(), decision, scope, false, reason, script, by != null ? by : operator(), env);
        }
    }

    static int decide(Path store, String decision, String scope, boolean approve, String reason, File script, String by, WeaveEnv env) {
        Optional<DecisionDef> def = definition(store, Names.check(decision), script, env.err());
        if (def.isEmpty()) return 2;
        Engine engine = new Engine(def.get(), AutonomySupport.ledger(store), AutonomySupport.levels(store), env.clock());
        try {
            Engine.Change change = approve ? engine.approve(scope, by, reason) : engine.reject(scope, by, reason);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", decision);
            data.put("scope", scope);
            data.put("rule", approve ? "promotion approved" : "promotion rejected");
            data.put("to", change.to().word());
            data.put("reason", reason);
            audit(store, approve ? "promotion_approved" : "promotion_rejected", data);
            env.out().println(approve ? "Approved: " + scope + " moved to " + change.to().word() + "." : "Rejected the promotion of " + scope + " to " + change.to().word() + ".");
            return 0;
        } catch (IllegalStateException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    // ---- freeze, unfreeze ----------------------------------------------------------------------------------------------------------

    private abstract static class Freezer implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        File store;

        @Parameters(index = "1", arity = "0..1", description = "The decision (default: all decisions).")
        String decision;

        @Option(names = "--reason", required = true, description = "Why (recorded).")
        String reason;
    }

    @Command(name = "freeze", description = "Stops act at once for a decision (or all): cases that begin after this run at suggest at most. A case in progress finishes as it began.")
    static class Freeze extends Freezer {
        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return freeze(store.toPath(), decision, true, reason, env);
        }
    }

    @Command(name = "unfreeze", description = "Lifts a freeze; each scope runs at the level it earned.")
    static class Unfreeze extends Freezer {
        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return freeze(store.toPath(), decision, false, reason, env);
        }
    }

    static int freeze(Path store, String decision, boolean on, String reason, WeaveEnv env) {
        String target = decision == null ? "*" : Names.check(decision);
        List<String> recordIn = decision == null ? AutonomySupport.decisions(store) : List.of(target);
        if (recordIn.isEmpty()) {
            env.err().println("Error: no decision has run from " + store + " yet.");
            return 2;
        }
        Ledger ledger = AutonomySupport.ledger(store);
        LevelStore levels = AutonomySupport.levels(store);
        for (String name : recordIn) {
            if (on) Engine.freeze(ledger, levels, env.clock(), name, target, reason);
            else Engine.unfreeze(ledger, levels, env.clock(), name, target, reason);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", target);
        data.put("rule", "by hand");
        data.put("reason", reason);
        audit(store, on ? "frozen" : "unfrozen", data);
        env.out().println((on ? "Frozen " : "Unfroze ") + (decision == null ? "every decision" : decision) + (on ? ": act is stopped for cases that begin from now." : "."));
        return 0;
    }

    // ---- outcome ----------------------------------------------------------------------------------------------------------------

    @Command(name = "outcome", description = "Records a later fact about a case (reversed, upheld, …). A reversal of a verdict the agent made counts against it.")
    static class Outcome implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        File store;

        @Parameters(index = "1", description = "The decision.")
        String decision;

        @Parameters(index = "2", description = "The case id (shown by status and in the ledger).")
        String caseId;

        @Option(names = "--result", required = true, description = "reversed, upheld, or another word.")
        String result;

        @Option(names = "--note", description = "A note (recorded).")
        String note;

        @Option(names = "--script", description = "The script that declares the decision.")
        File script;

        @Override
        public Integer call() {
            WeaveEnv env = WeaveEnv.system();
            return outcome(store.toPath(), decision, caseId, result, note, script, env);
        }
    }

    static int outcome(Path store, String decision, String caseId, String result, String note, File script, WeaveEnv env) {
        Optional<DecisionDef> def = definition(store, Names.check(decision), script, env.err());
        if (def.isEmpty()) return 2;
        Engine engine = new Engine(def.get(), AutonomySupport.ledger(store), AutonomySupport.levels(store), env.clock());
        try {
            List<Engine.Change> changes = engine.outcome(caseId, result, note);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decision", decision);
            data.put("case", caseId);
            data.put("result", result);
            if (note != null) data.put("note", note);
            data.put("changes", changes.stream().map(Engine.Change::sentence).toList());
            audit(store, "outcome_recorded", data);
            env.out().println("Recorded: " + caseId + " was " + result + ".");
            for (Engine.Change c : changes) env.out().println("  " + c.sentence());
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }
}
