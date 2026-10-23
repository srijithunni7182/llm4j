package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.review.tools.RulesTool;
import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave rules}: the offline tools for the rules {@code weave review} applies: list them,
 * print the facts they are written against, check and test a pack, scaffold a rule, add a pack to a
 * project and pin it, and show why a rule did or did not fire at one place.
 */
@Command(
        name = "rules",
        description =
                "Works with review rules and rule packs, offline: list, facts, check, test, new, add, diff, docs, explain.",
        subcommands = {
            RulesCommand.ListRules.class,
            RulesCommand.Facts.class,
            RulesCommand.Check.class,
            RulesCommand.Test.class,
            RulesCommand.New.class,
            RulesCommand.Add.class,
            RulesCommand.Diff.class,
            RulesCommand.Docs.class,
            RulesCommand.Explain.class
        })
final class RulesCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        WeaveEnv env = WeaveEnv.system();
        env.err()
                .println(
                        "weave rules needs a subcommand: list, facts, check, test, new, add, diff, docs or explain. Try weave rules --help.");
        return 2;
    }

    private static RulesTool tool() {
        WeaveEnv env = WeaveEnv.system();
        return new RulesTool(env.out(), env.err());
    }

    @Command(name = "list", description = "Lists the rule packs in force and their rules.")
    static final class ListRules implements Callable<Integer> {
        @Option(
                names = "--dir",
                description =
                        "The project folder (review.properties and review-rules/). Default: here.",
                defaultValue = ".")
        File dir;

        @Override
        public Integer call() {
            return tool().list(dir.toPath().toAbsolutePath().normalize());
        }
    }

    @Command(
            name = "facts",
            description = "Prints the fact catalog that rules are written against.")
    static final class Facts implements Callable<Integer> {
        @Override
        public Integer call() {
            return tool().facts();
        }
    }

    @Command(name = "check", description = "Checks that a rule pack is sound and prints its hash.")
    static final class Check implements Callable<Integer> {
        @Parameters(index = "0", description = "The pack folder, or 'core'.")
        String pack;

        @Override
        public Integer call() {
            return tool().check(pack);
        }
    }

    @Command(
            name = "test",
            description = "Runs the firing and quiet examples of every rule in a pack.")
    static final class Test implements Callable<Integer> {
        @Parameters(index = "0", description = "The pack folder, or 'core'.")
        String pack;

        @Override
        public Integer call() {
            return tool().test(pack);
        }
    }

    @Command(
            name = "new",
            description = "Adds a rule skeleton and its two example scripts to a pack.")
    static final class New implements Callable<Integer> {
        @Parameters(index = "0", description = "The new rule's id, such as ACME-001.")
        String id;

        @Option(
                names = "--pack",
                description = "The pack folder. Default: here.",
                defaultValue = ".")
        File pack;

        @Override
        public Integer call() {
            return tool().scaffold(id, pack.toPath());
        }
    }

    @Command(
            name = "add",
            description =
                    "Copies a rule pack into this project's review-rules/ and pins its version and hash in review.properties. Nothing is downloaded.")
    static final class Add implements Callable<Integer> {
        @Parameters(index = "0", description = "The pack folder to copy in.")
        File source;

        @Option(
                names = "--dir",
                description = "The project folder. Default: here.",
                defaultValue = ".")
        File dir;

        @Override
        public Integer call() {
            return tool().add(source.toPath(), dir.toPath().toAbsolutePath().normalize());
        }
    }

    @Command(
            name = "diff",
            description =
                    "Compares two versions of a rule pack: rules added, removed and changed, the version bump the change needs, and (with --on) the findings that would appear or disappear on a script.")
    static final class Diff implements Callable<Integer> {
        @Parameters(index = "0", description = "The older pack folder, or 'core'.")
        String from;

        @Parameters(index = "1", description = "The newer pack folder, or 'core'.")
        String to;

        @Option(names = "--on", description = "A script to review with both versions.")
        File script;

        @Option(names = "--markdown", description = "Print a changelog entry.")
        boolean markdown;

        @Override
        public Integer call() {
            return tool().diff(from, to, script == null ? null : script.toPath(), markdown);
        }
    }

    @Command(
            name = "docs",
            description =
                    "Writes the rule reference (one page per rule and an index) generated from a pack and the built-in rules.")
    static final class Docs implements Callable<Integer> {
        @Parameters(index = "0", description = "The pack folder, or 'core'.")
        String pack;

        @Option(
                names = "--out",
                description = "The folder to write to. Default: docs/review/rules.",
                defaultValue = "docs/review/rules")
        File out;

        @Override
        public Integer call() {
            return tool().docs(pack, out.toPath());
        }
    }

    @Command(
            name = "explain",
            description =
                    "Shows, for one rule at one place, every condition and the value it saw, so you can see why the rule did or did not fire.")
    static final class Explain implements Callable<Integer> {
        @Parameters(index = "0", description = "The rule id.")
        String rule;

        @Option(
                names = "--at",
                required = true,
                paramLabel = "<file:line>",
                description = "Where to look, such as main.loom:42.")
        String at;

        @Override
        public Integer call() {
            return tool().explainAt(rule, at);
        }
    }
}
