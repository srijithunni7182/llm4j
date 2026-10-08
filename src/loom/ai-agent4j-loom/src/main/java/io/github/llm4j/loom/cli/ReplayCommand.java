package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.autonomy.Names;
import io.github.llm4j.loom.autonomy.ReplayEngine;
import io.github.llm4j.loom.autonomy.ReplayOptions;
import io.github.llm4j.loom.autonomy.ReplayReport;
import java.io.File;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave replay <script> --decision Name --store <dir>}: re-runs the past cases of a decision under a candidate script (a changed model,
 * prompt, policy or tools), with nothing sent and nothing changed, and grades the result against what people decided. See the "Earned autonomy" part
 * of the guide.
 */
@Command(name = "replay", description = "Replays a decision's past cases under a candidate script, with nothing sent and nothing changed, and reports how it would have done.")
final class ReplayCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "The script that declares the decision (the candidate, unless --candidate is given).")
    File script;

    @Option(names = "--decision", required = true, description = "The decision to replay.")
    String decision;

    @Option(names = "--store", required = true, description = "The run store the decision's cases are in.")
    File store;

    @Option(names = "--candidate", description = "A changed script to try instead of the one given.")
    File candidate;

    @Option(names = "--since", description = "Only cases decided since: a duration (14d, 12h) or a date (2026-03-01).")
    String since;

    @Option(names = "--scope", description = "Only this scope.")
    String scope;

    @Option(names = "--limit", description = "At most this many cases, after a seeded shuffle (default 500).")
    int limit;

    @Option(names = "--seed", defaultValue = "1", description = "The seed of the shuffle: the same seed gives the same sample.")
    long seed;

    @Option(names = "--repeat", defaultValue = "1", description = "Replay each case this many times and report how stable the choice is.")
    int repeat;

    @Option(names = "--live-reads", description = "A read the case did not record runs live (flagged non-deterministic) instead of making the case unreplayable.")
    boolean liveReads;

    @Option(names = "--allow-drift", description = "Accept a candidate whose script differs before the decide step (the cases are flagged).")
    boolean allowDrift;

    @Option(names = "--no-memory", description = "Do not run memory or knowledge reads in the replay.")
    boolean noMemory;

    @Option(names = "--max-tokens", description = "Stop cleanly after this many tokens.")
    long maxTokens;

    @Option(names = "--max-cost", description = "Stop cleanly after spending this much (replayed models need a price table).")
    String maxCost;

    @Option(names = "--policy", description = "A file that replaces the file of the same name the candidate's agent reads, for the candidate only.")
    File policy;

    @Option(names = "--format", defaultValue = "md", description = "md or json.")
    String format;

    @Option(names = "--report", description = "Write the report to this file as well.")
    File report;

    @Option(names = "--resume", description = "Carry on a replay that was cut short, by its id.")
    String resume;

    @picocli.CommandLine.Mixin
    PromptOptions promptOptions = new PromptOptions();

    @Override
    public Integer call() {
        WeaveEnv env = promptOptions.apply(WeaveEnv.system());
        if (env == null) return 2;
        return replay(this, env);
    }

    static Instant parseSince(String text, Clock clock) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(\\d+)([dhm])").matcher(text.strip());
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            Duration d = switch (m.group(2)) {
                case "d" -> Duration.ofDays(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofMinutes(n);
            };
            return clock.instant().minus(d);
        }
        return LocalDate.parse(text.strip()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    static int replay(ReplayCommand c, WeaveEnv env) {
        PrintStream out = env.out(), err = env.err();
        try {
            Names.check(c.decision);
            ReplayEngine engine = AutonomySupport.engine(c.store.toPath(), c.decision, env);
            ReplayReport result;
            if (c.resume != null) {
                result = engine.resume(c.decision, c.resume, c.maxCost == null ? null : new BigDecimal(c.maxCost), c.maxTokens);
            } else {
                Path candidate = (c.candidate != null ? c.candidate : c.script) == null ? null : (c.candidate != null ? c.candidate : c.script).toPath().toAbsolutePath();
                if (candidate == null || !Files.exists(candidate)) {
                    err.println("Error: give the script to replay under (a script argument or --candidate).");
                    return 2;
                }
                ReplayOptions options = new ReplayOptions(candidate, c.scope, parseSince(c.since, env.clock()), c.limit, c.seed, c.repeat, c.liveReads, c.allowDrift, c.noMemory,
                        c.maxTokens, c.maxCost == null ? null : new BigDecimal(c.maxCost), c.policy == null ? null : c.policy.toPath().toAbsolutePath());
                result = engine.run(c.decision, options, null);
            }
            String text = "json".equals(c.format) ? result.json() : result.markdown();
            out.println(text);
            if (c.report != null) Files.writeString(c.report.toPath(), text);
            err.println("Replay " + result.id + " kept in " + AutonomySupport.dir(c.store.toPath()).resolve(c.decision).resolve("replays").resolve(result.id));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
