package io.github.llm4j.loom.guide;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The written guide, from inside the jar: the chapters, the Loom reference and a short index for
 * tools, and the skill that teaches an agent to use them. They are copied in when the jar is built,
 * so they are there for someone who has the jar and nothing else.
 */
public final class Guide {

    /** One page of the guide: the name to ask for, what it is, and where it is in the jar. */
    public record Page(String name, String title, String resource) {}

    private static final List<Page> PAGES =
            List.of(
                    new Page(
                            "readme",
                            "The path, and whether you want tests first",
                            "guide/README.md"),
                    new Page(
                            "1",
                            "Decide your agents (and: a script, or Java)",
                            "guide/01-decide-your-agents.md"),
                    new Page(
                            "2", "Build a golden dataset (optional)", "guide/02-golden-dataset.md"),
                    new Page("3", "Test the prompts", "guide/03-prompt-tests.md"),
                    new Page("4", "Optimize the prompts", "guide/04-prompt-optimization.md"),
                    new Page(
                            "5",
                            "Test each agent, with spend caps",
                            "guide/05-test-agents-with-caps.md"),
                    new Page("6", "Build the workflow", "guide/06-build-the-workflow.md"),
                    new Page("7", "Validate and audit", "guide/07-validate-and-audit.md"),
                    new Page("8", "Test the trajectory", "guide/08-trajectory-tests.md"),
                    new Page(
                            "9", "Go live: real APIs, budgets, cost checks", "guide/09-go-live.md"),
                    new Page("10", "Best practices and checklist", "guide/10-best-practices.md"),
                    new Page(
                            "11",
                            "Build the test examples together with the user",
                            "guide/11-build-the-dataset-with-the-user.md"),
                    new Page(
                            "12",
                            "Java quick reference: the imports and calls you need",
                            "guide/12-java-quick-reference.md"),
                    new Page(
                            "13",
                            "Earned autonomy: let an agent earn the right to decide (optional)",
                            "guide/13-earned-autonomy.md"),
                    new Page(
                            "14",
                            "Context and compaction: keep a long run inside the model's window",
                            "guide/14-context-and-compaction.md"),
                    new Page(
                            "15",
                            "Steer, stop and supervise a running workflow (optional)",
                            "guide/15-steer-stop-and-supervise.md"),
                    new Page(
                            "16",
                            "Cost and model choice: suggest models from evidence, estimate and cap the cost",
                            "guide/16-cost-and-model-choice.md"),
                    new Page(
                            "agents",
                            "Instructions for a coding agent: look things up, the loop, the rules",
                            "guide/AGENT_INSTRUCTIONS.md"),
                    new Page(
                            "recipes",
                            "Recipes: change the starter to do what you want",
                            "guide/RECIPES.md"),
                    new Page(
                            "loom",
                            "The Loom reference: every statement, option and command",
                            "guide/LOOM_GUIDE.md"),
                    new Page(
                            "llms",
                            "A one-page index of the framework, for tools and agents",
                            "guide/llms.txt"),
                    new Page(
                            "deploy",
                            "Deploying a workflow, watched: the map of the deployer skill",
                            "deploy/README.md"),
                    new Page(
                            "d1",
                            "Deploy 1: decide the shape (one page, or a Fabric)",
                            "deploy/01-decide-the-shape.md"),
                    new Page(
                            "d2",
                            "Deploy 2: security decisions before anything runs for real",
                            "deploy/02-security-before-you-run.md"),
                    new Page(
                            "d3",
                            "Deploy 3: the rules, the windows and the prices",
                            "deploy/03-rules-windows-prices.md"),
                    new Page(
                            "d4",
                            "Deploy 4: launch the application with its own page",
                            "deploy/04-run-with-its-own-page.md"),
                    new Page(
                            "d5",
                            "Deploy 5: bring up a Fabric server",
                            "deploy/05-bring-up-fabric.md"),
                    new Page(
                            "d6",
                            "Deploy 6: launch the application reporting to Fabric",
                            "deploy/06-run-reporting-to-fabric.md"),
                    new Page(
                            "d7",
                            "Deploy 7: read the pages, and what to do about each incident",
                            "deploy/07-read-the-pages.md"),
                    new Page(
                            "d8",
                            "Deploy 8: operate it, and fix what goes wrong",
                            "deploy/08-operate-and-troubleshoot.md"));

    /**
     * The launch scripts that travel with the deployer skill (macOS and Linux {@code .sh}, Windows
     * {@code .ps1}).
     */
    public static final List<String> DEPLOYER_SCRIPTS =
            List.of(
                    "lib.sh",
                    "fabric-up.sh",
                    "fabric-status.sh",
                    "fabric-down.sh",
                    "run-observed.sh",
                    "lib.ps1",
                    "fabric-up.ps1",
                    "fabric-status.ps1",
                    "fabric-down.ps1",
                    "run-observed.ps1");

    /** The references that travel with the harness-design skill. */
    public static final List<String> HARNESS_REFERENCES =
            List.of(
                    "principles.md",
                    "failure-modes.md",
                    "loom-mapping.md",
                    "design-record.md",
                    "conventions.md",
                    "cost-and-models.md");

    /** The references that travel with the evaluation-design skill. */
    public static final List<String> EVAL_REFERENCES =
            List.of(
                    "principles.md",
                    "oracles.md",
                    "dataset-and-labelling.md",
                    "quality-dimensions.md",
                    "security-testing.md",
                    "trajectory-and-multi-turn.md",
                    "judge-and-pipeline.md",
                    "eval4j-loom-mapping.md",
                    "failure-modes.md",
                    "design-record.md",
                    "conventions.md");

    private Guide() {}

    public static List<Page> pages() {
        return PAGES;
    }

    /**
     * The page asked for: {@code 6}, {@code 06}, {@code loom}, {@code llms}, {@code readme}, or a
     * chapter's name such as {@code build-the-workflow}.
     */
    public static Optional<Page> find(String what) {
        String key = what.strip().toLowerCase(Locale.ROOT).replaceFirst("\\.md$", "");
        String number = key.matches("0\\d") ? key.substring(1) : key;
        for (Page p : PAGES) {
            String file =
                    p.resource()
                            .substring(p.resource().lastIndexOf('/') + 1)
                            .toLowerCase(Locale.ROOT)
                            .replaceFirst("\\.(md|txt)$", "");
            String named = file.replaceFirst("^\\d+-", "");
            if (p.name().equals(number)
                    || file.equals(key)
                    || named.equals(key)
                    || (p.name().equals("loom") && key.equals("loom_guide"))) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** The text of a page, or empty when the jar does not have it. */
    public static Optional<String> text(Page page) {
        return read(page.resource());
    }

    /**
     * The skill that teaches an agent to use the guide, with its chapter paths pointing at {@code
     * references/}.
     */
    public static Optional<String> skill() {
        return read("skill/SKILL.md").map(s -> s.replace("`docs/guide/", "`references/"));
    }

    public static Optional<String> read(String resource) {
        try (InputStream in = Guide.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null
                    ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The deployer skill, with its chapter paths pointing at {@code references/}. */
    public static Optional<String> deployerSkill() {
        return read("skill-deployer/SKILL.md").map(s -> s.replace("`docs/deploy/", "`references/"));
    }

    /** Where the deployer skill goes inside a project. */
    public static Path deployerFolder(Path project) {
        return project.resolve(".claude").resolve("skills").resolve("llm4j-loom-deployer");
    }

    /**
     * Writes the deployer skill into {@code project/.claude/skills/llm4j-loom-deployer}: {@code
     * SKILL.md}, {@code references/} (its chapters) and {@code scripts/} (the launch scripts,
     * executable). The jars the scripts launch are not in the jar of weave: the skill package
     * carries them in {@code bin/}. Without {@code force} nothing is written when the skill is
     * already there.
     *
     * @return the files written, relative to the skill folder; empty when it was already there
     */
    public static List<String> installDeployerSkill(Path project, boolean force)
            throws IOException {
        Path folder = deployerFolder(project);
        if (Files.exists(folder.resolve("SKILL.md")) && !force) return List.of();
        String skill =
                deployerSkill()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no deployer skill (a broken jar?)"));
        List<String> written = new ArrayList<>();
        Files.createDirectories(folder.resolve("references"));
        Files.createDirectories(folder.resolve("scripts"));
        Files.writeString(folder.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);
        written.add("SKILL.md");
        for (Page p : PAGES) {
            if (!p.resource().startsWith("deploy/")) continue;
            String name = p.resource().substring("deploy/".length());
            Files.writeString(
                    folder.resolve("references").resolve(name),
                    text(p).orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "this jar has no " + p.resource())),
                    StandardCharsets.UTF_8);
            written.add("references/" + name);
        }
        writeConventions(folder, "skill-deployer", written);
        for (String script : DEPLOYER_SCRIPTS) {
            Path target = folder.resolve("scripts").resolve(script);
            Files.writeString(
                    target,
                    read("skill-deployer/scripts/" + script)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "this jar has no script " + script)),
                    StandardCharsets.UTF_8);
            if (script.endsWith(".sh")) target.toFile().setExecutable(true, false);
            written.add("scripts/" + script);
        }
        return written;
    }

    /** Where the harness-design skill goes inside a project. */
    public static Path harnessFolder(Path project) {
        return project.resolve(".claude").resolve("skills").resolve("llm4j-harness-design");
    }

    /**
     * Writes the harness-design skill into {@code project/.claude/skills/llm4j-harness-design}:
     * {@code SKILL.md} and {@code references/}. It is text only: it needs no jar and no script.
     * Without {@code force} nothing is written when the skill is already there.
     *
     * @return the files written, relative to the skill folder; empty when it was already there
     */
    public static List<String> installHarnessSkill(Path project, boolean force) throws IOException {
        Path folder = harnessFolder(project);
        if (Files.exists(folder.resolve("SKILL.md")) && !force) return List.of();
        String skill =
                read("skill-harness/SKILL.md")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no harness-design skill (a broken jar?)"));
        List<String> written = new ArrayList<>();
        Files.createDirectories(folder.resolve("references"));
        Files.writeString(folder.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);
        written.add("SKILL.md");
        for (String ref : HARNESS_REFERENCES) {
            Files.writeString(
                    folder.resolve("references").resolve(ref),
                    read("skill-harness/references/" + ref)
                            .orElseThrow(() -> new IllegalStateException("this jar has no " + ref)),
                    StandardCharsets.UTF_8);
            written.add("references/" + ref);
        }
        return written;
    }

    /** Where the evaluation-design skill goes inside a project. */
    public static Path evalFolder(Path project) {
        return project.resolve(".claude").resolve("skills").resolve("llm4j-eval-design");
    }

    /**
     * Writes the evaluation-design skill into {@code project/.claude/skills/llm4j-eval-design}:
     * {@code SKILL.md} and {@code references/}. It is text only: it needs no jar and no script.
     * Without {@code force} nothing is written when the skill is already there.
     *
     * @return the files written, relative to the skill folder; empty when it was already there
     */
    public static List<String> installEvalSkill(Path project, boolean force) throws IOException {
        Path folder = evalFolder(project);
        if (Files.exists(folder.resolve("SKILL.md")) && !force) return List.of();
        String skill =
                read("skill-eval/SKILL.md")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no evaluation-design skill (a broken jar?)"));
        List<String> written = new ArrayList<>();
        Files.createDirectories(folder.resolve("references"));
        Files.writeString(folder.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);
        written.add("SKILL.md");
        for (String ref : EVAL_REFERENCES) {
            Files.writeString(
                    folder.resolve("references").resolve(ref),
                    read("skill-eval/references/" + ref)
                            .orElseThrow(() -> new IllegalStateException("this jar has no " + ref)),
                    StandardCharsets.UTF_8);
            written.add("references/" + ref);
        }
        return written;
    }

    /** The rules every skill shares, copied from the jar into the skill's references. */
    private static void writeConventions(Path folder, String resourceRoot, List<String> written)
            throws IOException {
        Files.writeString(
                folder.resolve("references").resolve("conventions.md"),
                read(resourceRoot + "/references/conventions.md")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no conventions.md for "
                                                        + resourceRoot)),
                StandardCharsets.UTF_8);
        written.add("references/conventions.md");
    }

    /** Where the skill goes inside a project. */
    public static Path skillFolder(Path project) {
        return project.resolve(".claude").resolve("skills").resolve("llm4j-workflow-guide");
    }

    /**
     * Writes the skill and the pages it points at into {@code
     * project/.claude/skills/llm4j-workflow-guide} ({@code SKILL.md} and {@code references/}).
     * Without {@code force} nothing is written when the skill is already there.
     *
     * @return the files written, relative to the skill folder; empty when it was already there
     */
    public static List<String> installSkill(Path project, boolean force) throws IOException {
        Path folder = skillFolder(project);
        if (Files.exists(folder.resolve("SKILL.md")) && !force) return List.of();
        String skill =
                skill().orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no skill (a broken jar?)"));
        List<String> written = new ArrayList<>();
        Files.createDirectories(folder.resolve("references"));
        Files.writeString(folder.resolve("SKILL.md"), skill, StandardCharsets.UTF_8);
        written.add("SKILL.md");
        for (Page p : PAGES) {
            if (!p.resource().startsWith("guide/")) continue;
            String name = p.resource().substring("guide/".length());
            Files.writeString(
                    folder.resolve("references").resolve(name),
                    text(p).orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "this jar has no " + p.resource())),
                    StandardCharsets.UTF_8);
            written.add("references/" + name);
        }
        writeConventions(folder, "skill", written);
        // the launcher for a terminal with no `weave` on its PATH
        for (String script : new String[] {"weave", "weave.cmd"}) {
            Optional<String> text = read("skill/scripts/" + script);
            if (text.isEmpty()) continue;
            Path target = folder.resolve("scripts").resolve(script);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text.get(), StandardCharsets.UTF_8);
            target.toFile().setExecutable(true, false);
            written.add("scripts/" + script);
        }
        return written;
    }

    // ---- instructions for coding agents that do not read .claude/skills

    /** The coding agents whose own instruction files can be written. */
    public enum Agent {
        /**
         * The cross-agent file (Codex, Copilot's agent mode, Gemini, Cursor and others read it).
         */
        AGENTS("AGENTS.md", "AGENTS.md"),
        COPILOT("copilot", ".github/copilot-instructions.md"),
        CURSOR("cursor", ".cursor/rules/loom.mdc");

        final String flag;
        final String file;

        Agent(String flag, String file) {
            this.flag = flag;
            this.file = file;
        }

        public String file() {
            return file;
        }

        /** The name given to {@code --for}. */
        public String flag() {
            return flag.equals("AGENTS.md") ? "agents" : flag;
        }
    }

    static final String BEGIN =
            "<!-- loom:begin (written by weave guide --install-skill; edit outside this block) -->";
    static final String END = "<!-- loom:end -->";

    /** What was done with one file. */
    public record Written(Agent agent, Path file, String what) {}

    /**
     * Writes the instructions for a coding agent into its own file under {@code project}. The text
     * sits between two marker lines, so what a person wrote in the same file stays: a file that has
     * no block gets one appended; a block that is already there is left alone unless {@code force}
     * replaces it (and only it).
     */
    public static Written installAgentInstructions(Path project, Agent agent, boolean force)
            throws IOException {
        String body =
                read("guide/AGENT_INSTRUCTIONS.md")
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this jar has no guide/AGENT_INSTRUCTIONS.md (a broken jar?)"));
        String block = BEGIN + "\n" + body.strip() + "\n" + END + "\n";
        Path file = project.resolve(agent.file());
        Files.createDirectories(file.getParent());
        String existing =
                Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (existing == null) {
            String head =
                    agent == Agent.CURSOR
                            ? "---\ndescription: How to build, test and ship Loom workflows with weave\nglobs: \"**/*.loom,**/*.test.yaml,**/tools.loot\"\nalwaysApply: false\n---\n\n"
                            : "";
            Files.writeString(file, head + block, StandardCharsets.UTF_8);
            return new Written(agent, file, "created");
        }
        int from = existing.indexOf(BEGIN);
        int to = existing.indexOf(END);
        if (from < 0 || to < from) {
            String sep = existing.endsWith("\n\n") ? "" : existing.endsWith("\n") ? "\n" : "\n\n";
            Files.writeString(file, existing + sep + block, StandardCharsets.UTF_8);
            return new Written(agent, file, "added to the end");
        }
        if (!force) return new Written(agent, file, "already there");
        String replaced =
                existing.substring(0, from)
                        + block
                        + existing.substring(to + END.length()).replaceFirst("^\\R", "");
        Files.writeString(file, replaced, StandardCharsets.UTF_8);
        return new Written(agent, file, "replaced");
    }
}
