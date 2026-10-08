package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.explain.ScriptExplainer;
import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave explain}: what a script does, in plain English. It reads the script (and what it imports) and nothing else: no model is asked,
 * no key is read and nothing is run, and the same script always gives the same words.
 */
@Command(name = "explain", description = "Describes a script in plain English (which agent is asked what, where a person is asked, what can spend money) without running anything or calling a model.")
final class ExplainCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(names = {"-l", "--loot"}, description = "Accepted so the same options work for check, audit, eval and explain; explain describes the script and does not load your tools.")
    java.io.File lootFile;

    @Option(names = "--workflow", description = "Only this workflow.")
    String workflow;

    @Override
    public Integer call() {
        return explain(this, WeaveEnv.system());
    }

    static int explain(ExplainCommand c, WeaveEnv env) {
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(c.script.getAbsolutePath());
        } catch (Exception e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
        if (c.workflow != null && loaded.getWorkflows().stream().noneMatch(w -> w.getName().equals(c.workflow))) {
            String known = loaded.getWorkflows().stream().map(io.github.llm4j.loom.ast.WorkflowDef::getName).collect(java.util.stream.Collectors.joining(", "));
            env.err().println("Error: no workflow named " + c.workflow + ". Known workflows: " + (known.isEmpty() ? "none" : known) + ".");
            return 2;
        }
        env.out().print(ScriptExplainer.explain(loaded, c.script.getName(), c.workflow));
        return 0;
    }
}
