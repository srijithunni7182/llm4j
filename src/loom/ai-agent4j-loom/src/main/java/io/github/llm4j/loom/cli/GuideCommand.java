package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.guide.Guide;
import io.github.llm4j.loom.prompt.PromptCatalog;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave guide}: the written guide, from inside the jar. With nothing it lists the pages;
 * with a page it prints it. {@code --install-skill} puts the skill that teaches an agent to use the
 * guide (and the guide itself) into a project.
 */
@Command(
        name = "guide",
        description =
                "Prints the guide that ships inside this jar: weave guide (list), weave guide 6, weave guide loom. --install-skill adds the agent skill to a project.")
final class GuideCommand implements Callable<Integer> {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<page>",
            description =
                    "A chapter number (1 to 14), readme, loom (the reference), llms, deploy (the deployer skill's map) or d1 to d8.")
    String page;

    @Option(
            names = "--install-skill",
            description =
                    "Write the skills and the guide into <dir>/.claude/skills (default: this folder): llm4j-harness-design (design the harness), llm4j-workflow-guide (build, test, secure, ship) and llm4j-loom-deployer (run it, watched).",
            arity = "0..1",
            fallbackValue = ".")
    File installTo;

    @Option(
            names = "--skill",
            description = "With --install-skill: harness, workflow, deployer or all (default all).",
            defaultValue = "all")
    String which;

    @Option(
            names = "--for",
            description =
                    "With --install-skill: which coding agents to set up, separated by commas: claude (the skills in .claude/skills; the default), agents (AGENTS.md, read by Codex, Copilot, Gemini, Cursor and others), copilot (.github/copilot-instructions.md), cursor (.cursor/rules/loom.mdc) or all. The instruction files keep what you wrote in them: Loom's text sits between two marker lines.",
            defaultValue = "claude")
    String forAgents;

    @Option(
            names = "--force",
            description = "With --install-skill: replace the skill if it is already there.")
    boolean force;

    @Override
    public Integer call() {
        return guide(this, WeaveEnv.system());
    }

    static int guide(GuideCommand c, WeaveEnv env) {
        if (c.installTo != null) {
            if (!List.of("all", "harness", "workflow", "deployer").contains(c.which)) {
                env.err()
                        .println(
                                "Error: --skill is harness, workflow, deployer or all (not "
                                        + c.which
                                        + ").");
                return 2;
            }
            java.util.Set<String> targets = new java.util.LinkedHashSet<>();
            for (String t : c.forAgents.split("\\s*,\\s*")) targets.add(t.toLowerCase());
            if (targets.contains("all"))
                targets.addAll(List.of("claude", "agents", "copilot", "cursor"));
            targets.remove("all");
            for (String t : targets)
                if (!List.of("claude", "agents", "copilot", "cursor").contains(t)) {
                    env.err()
                            .println(
                                    "Error: --for is claude, agents, copilot, cursor or all, separated by commas (not "
                                            + t
                                            + ").");
                    return 2;
                }
            try {
                Path project = c.installTo.toPath().toAbsolutePath().normalize();
                int installed = 0;
                for (Guide.Agent agent : Guide.Agent.values()) {
                    if (!targets.contains(agent.flag())) continue;
                    Guide.Written w = Guide.installAgentInstructions(project, agent, c.force);
                    if (w.what().equals("already there"))
                        env.err()
                                .println(
                                        "The instructions are already in "
                                                + w.file()
                                                + ". Use --force to replace them (only the block between the marker lines).");
                    else {
                        installed++;
                        env.out()
                                .println(
                                        "Wrote the instructions for coding agents ("
                                                + w.what()
                                                + ") in "
                                                + w.file()
                                                + ": how to look commands up (weave man), the loop and the rules.");
                    }
                }
                boolean claude = targets.contains("claude");
                if (claude && (c.which.equals("all") || c.which.equals("harness"))) {
                    List<String> written = Guide.installHarnessSkill(project, c.force);
                    if (written.isEmpty())
                        env.err()
                                .println(
                                        "The skill is already in "
                                                + Guide.harnessFolder(project)
                                                + ". Use --force to replace it.");
                    else {
                        installed++;
                        env.out()
                                .println(
                                        "Installed the skill in "
                                                + Guide.harnessFolder(project)
                                                + " ("
                                                + written.size()
                                                + " files): it designs the harness around a workflow and writes a HARNESS.md before the workflow is built.");
                    }
                }
                if (claude && (c.which.equals("all") || c.which.equals("workflow"))) {
                    List<String> written = Guide.installSkill(project, c.force);
                    if (written.isEmpty())
                        env.err()
                                .println(
                                        "The skill is already in "
                                                + Guide.skillFolder(project)
                                                + ". Use --force to replace it.");
                    else {
                        installed++;
                        env.out()
                                .println(
                                        "Installed the skill in "
                                                + Guide.skillFolder(project)
                                                + " ("
                                                + written.size()
                                                + " files). An agent that reads this project will find it.");
                    }
                }
                if (claude && (c.which.equals("all") || c.which.equals("deployer"))) {
                    List<String> written = Guide.installDeployerSkill(project, c.force);
                    if (written.isEmpty())
                        env.err()
                                .println(
                                        "The skill is already in "
                                                + Guide.deployerFolder(project)
                                                + ". Use --force to replace it.");
                    else {
                        installed++;
                        env.out()
                                .println(
                                        "Installed the skill in "
                                                + Guide.deployerFolder(project)
                                                + " ("
                                                + written.size()
                                                + " files): it takes a finished workflow to a running, watched one. Its scripts are in scripts/; the jars they launch come with the skill package (bin/) or from FABRIC_JAR, WEAVE_JAR, INSPECT_JAR and INSPECT_CORE_JAR.");
                    }
                }
                return installed == 0 ? 2 : 0;
            } catch (java.io.IOException | IllegalStateException e) {
                env.err().println("Error: could not install the skill: " + e.getMessage());
                return 2;
            }
        }
        if (c.page == null) {
            env.out()
                    .println(
                            "The guide, from inside this jar. Read a page with: weave guide <page>");
            for (Guide.Page p : Guide.pages())
                env.out().println("  " + String.format("%-7s", p.name()) + p.title());
            return 0;
        }
        var found = Guide.find(c.page);
        if (found.isEmpty()) {
            List<String> near =
                    PromptCatalog.nearest(
                            c.page, Guide.pages().stream().map(Guide.Page::name).toList());
            env.err()
                    .println(
                            "Error: there is no page named "
                                    + c.page
                                    + (near.isEmpty()
                                            ? ""
                                            : "; did you mean " + String.join(", ", near) + "?")
                                    + " Run weave guide to see them.");
            return 2;
        }
        var text = Guide.text(found.get());
        if (text.isEmpty()) {
            env.err()
                    .println(
                            "Error: this jar does not contain "
                                    + found.get().resource()
                                    + ". Reinstall weave.");
            return 2;
        }
        env.out().print(text.get());
        return 0;
    }
}
