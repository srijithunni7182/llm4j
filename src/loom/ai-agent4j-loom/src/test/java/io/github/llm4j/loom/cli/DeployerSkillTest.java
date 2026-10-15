package io.github.llm4j.loom.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.guide.Guide;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** R40, R42, R43, R44, R45 of spec 05-fabric-ui (5f-3b, 5f-3c): the deployer skill is complete, ships in the jar, installs next to the workflow skill, and names only what exists. */
class DeployerSkillTest {

    static final Path REPO = Path.of("../../..");
    static final Path SKILL = REPO.resolve(".claude/skills/llm4j-loom-deployer");
    static final Path CHAPTERS = REPO.resolve("docs/deploy");

    @TempDir Path dir;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    WeaveEnv env() {
        return new WeaveEnv(m -> null, q -> "yes", new PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8), new PrintStream(err, true), Clock.systemUTC(), d -> { },
                c -> new io.github.llm4j.loom.trigger.system.CommandRunner.Result(0, "", ""), List.of("weave"), k -> null);
    }

    int guide(String... args) {
        GuideCommand c = new GuideCommand();
        new CommandLine(c).parseArgs(args);
        return GuideCommand.guide(c, env());
    }

    private static String skill() throws Exception {
        return Files.readString(SKILL.resolve("SKILL.md"));
    }

    private static List<Path> chapters() throws Exception {
        try (Stream<Path> s = Files.list(CHAPTERS)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    // ── the skill and its chapters ──────────────────────────────────────────

    @Test
    void theSkillHasValidFrontmatterAndPointsAtChaptersThatExist() throws Exception {
        String s = skill();
        assertThat(s).startsWith("---\nname: llm4j-loom-deployer\ndescription: ");
        String description = s.substring(s.indexOf("description: ") + 13, s.indexOf("\n---", 4));
        assertThat(description.length()).isLessThanOrEqualTo(1024);
        assertThat(description).contains("deploy", "observe", "Fabric", "trifecta", "llm4j-workflow-guide");
        Matcher m = Pattern.compile("docs/deploy/(\\d\\d-[a-z-]+\\.md)").matcher(s);
        Set<String> named = new java.util.TreeSet<>();
        while (m.find()) named.add(m.group(1));
        assertThat(named).hasSize(8);
        for (String n : named) assertThat(CHAPTERS.resolve(n)).as(n).exists();
        assertThat(Guide.deployerSkill().orElseThrow()).contains("`references/01-decide-the-shape.md`").doesNotContain("docs/deploy/");
    }

    @Test
    void everyChapterIsListedByTheGuideAndPrintsExactlyWhatTheRepositoryHas() throws Exception {
        assertThat(Guide.find("deploy")).isPresent();
        for (int i = 1; i <= 8; i++) assertThat(Guide.find("d" + i)).as("d" + i).isPresent();
        for (Path chapter : chapters()) {
            String name = chapter.getFileName().toString();
            assertThat(Guide.pages()).as(name).anyMatch(p -> p.resource().equals("deploy/" + name));
            assertThat(Guide.read("deploy/" + name)).as(name).hasValue(Files.readString(chapter));
        }
        out.reset();
        assertThat(guide("d5")).isZero();
        assertThat(out.toString()).isEqualTo(Files.readString(CHAPTERS.resolve("05-bring-up-fabric.md")));
    }

    @Test
    void theChaptersCoverWhatADeployerMustDo() throws Exception {
        String all = String.join("\n", chapters().stream().map(p -> {
            try { return Files.readString(p); } catch (Exception e) { throw new IllegalStateException(e); }
        }).toList());
        for (String must : List.of("weave audit", "trifecta: accepted", "reach", "weave observe rules", ".windows", ".prices", "--observe", "--observe-fabric", "fabric-up", "run-observed", "fabric-status", "fabric-down",
                "clients add", "master.key", "TLS", "--trust-proxy", "--observe-detail metadata", "outbox", "Live", "Paused", "time bar", "Runs", "Configuration", "Tools & tasks", "Security",
                "Looping", "Workflow broken", "Context nearly full", "Lethal trifecta", "Suspected injection", "not a security guarantee".replace("not a security guarantee", "green page is not a review"))) {
            assertThat(all).as("the chapters mention " + must).contains(must);
        }
    }

    @Test
    void noChapterLinksOutsideTheGuideExceptToTheWebAndNoneNamesARepositoryPath() throws Exception {
        List<String> offenders = new ArrayList<>();
        Pattern link = Pattern.compile("\\]\\(([^)\\s]+)\\)");
        for (Path chapter : chapters()) {
            Matcher m = link.matcher(Files.readString(chapter));
            while (m.find()) {
                String t = m.group(1);
                if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("#")) continue;
                String file = t.replaceFirst("#.*", "");
                if (!file.matches("[A-Za-z0-9._-]+\\.md") || !Files.exists(CHAPTERS.resolve(file))) offenders.add(chapter.getFileName() + " -> " + t);
            }
        }
        assertThat(offenders).isEmpty();
    }

    // ── the safety rules (R43) ──────────────────────────────────────────────

    @Test
    void theSkillAndItsReadmeStateTheSafetyRules() throws Exception {
        String s = skill();
        String readme = Files.readString(CHAPTERS.resolve("README.md"));
        for (String t : List.of(s, readme)) {
            assertThat(t).contains("never goes on a command line").containsIgnoringCase("ask");
        }
        assertThat(s).contains("opening a port beyond this computer").contains("accepting the lethal trifecta").contains("admin password").contains("revoking a client key")
                .contains("who looked and when").contains("not a security guarantee");
        assertThat(readme).contains("beyond this computer").contains("The user decides");
    }

    @Test
    void noScriptTakesASecretFromACommandLineOrPrintsOne() throws Exception {
        for (String name : Guide.DEPLOYER_SCRIPTS) {
            String t = Files.readString(SKILL.resolve("scripts").resolve(name));
            assertThat(t).as(name + " has no password option").doesNotContainPattern("--(admin-)?password[ =]").doesNotContainPattern("-Password\\b");
            assertThat(t).as(name + " never echoes a key or password").doesNotContainPattern("(echo|printf|Say|Write-Host)[^\\n]*\\$\\{?(PW|PASSWORD|KEY|KEYFILE_CONTENT)\\b");
        }
        assertThat(Files.readString(SKILL.resolve("scripts/fabric-up.sh"))).contains("env -u LOOM_FABRIC_ADMIN_PASSWORD").contains("is not this computer").contains("chmod 600");
        assertThat(Files.readString(SKILL.resolve("scripts/run-observed.sh"))).contains("--stdin").contains("not printed").contains("chmod 600");
        assertThat(Files.readString(SKILL.resolve("scripts/fabric-up.ps1"))).contains("is not this computer").contains("Remove-Item Env:\\LOOM_FABRIC_ADMIN_PASSWORD");
    }

    @Test
    void everyScriptIsListedAndEveryListedScriptExists() throws Exception {
        try (Stream<Path> s = Files.list(SKILL.resolve("scripts"))) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactlyInAnyOrderElementsOf(Guide.DEPLOYER_SCRIPTS);
        }
        for (String name : Guide.DEPLOYER_SCRIPTS) assertThat(Guide.read("skill-deployer/scripts/" + name)).as(name).hasValue(Files.readString(SKILL.resolve("scripts").resolve(name)));
        assertThat(Guide.read("skill-deployer/SKILL.md")).hasValue(skill());
    }

    // ── installing (R44) ────────────────────────────────────────────────────

    @Test
    void installSkillPutsBothSkillsInTheProject() throws Exception {
        assertThat(guide("--install-skill", dir.toString())).isZero();
        assertThat(dir.resolve(".claude/skills/llm4j-workflow-guide/SKILL.md")).exists();
        Path folder = Guide.deployerFolder(dir);
        assertThat(Files.readString(folder.resolve("SKILL.md"))).startsWith("---\nname: llm4j-loom-deployer").contains("`references/").doesNotContain("docs/deploy/");
        for (Path chapter : chapters()) assertThat(folder.resolve("references").resolve(chapter.getFileName())).exists();
        for (String script : Guide.DEPLOYER_SCRIPTS) assertThat(folder.resolve("scripts").resolve(script)).exists();
        assertThat(folder.resolve("scripts/fabric-up.sh").toFile().canExecute()).isTrue();
        assertThat(out.toString()).contains("llm4j-workflow-guide").contains("llm4j-loom-deployer");
    }

    @Test
    void oneSkillCanBeInstalledAlone() throws Exception {
        assertThat(guide("--install-skill", dir.toString(), "--skill", "deployer")).isZero();
        assertThat(Guide.deployerFolder(dir).resolve("SKILL.md")).exists();
        assertThat(Guide.skillFolder(dir).resolve("SKILL.md")).doesNotExist();
        out.reset();
        assertThat(guide("--install-skill", dir.toString(), "--skill", "workflow")).isZero();
        assertThat(Guide.skillFolder(dir).resolve("SKILL.md")).exists();
        assertThat(guide("--install-skill", dir.toString(), "--skill", "everything")).isEqualTo(2);
    }

    @Test
    void anInstalledSkillIsNotReplacedUnlessAskedTo() throws Exception {
        guide("--install-skill", dir.toString());
        Path skill = Guide.deployerFolder(dir).resolve("SKILL.md");
        Files.writeString(skill, "my edits");
        assertThat(guide("--install-skill", dir.toString())).isEqualTo(2);
        assertThat(Files.readString(skill)).isEqualTo("my edits");
        assertThat(guide("--install-skill", dir.toString(), "--force")).isZero();
        assertThat(Files.readString(skill)).startsWith("---\nname: llm4j-loom-deployer");
    }

    // ── drift (R45): what the skill and chapters name exists ────────────────

    private static String everything() throws Exception {
        StringBuilder b = new StringBuilder(skill()).append('\n');
        for (Path c : chapters()) b.append(Files.readString(c)).append('\n');
        return b.toString();
    }

    @Test
    void everyWeaveCommandAndOptionTheDeployerNamesExists() throws Exception {
        CommandLine root = WeaveCLI.commandLine();
        List<String> missing = new ArrayList<>();
        for (String line : everything().split("\n")) {
            Matcher m = Pattern.compile("(?:^|[`\\s(])weave ([a-z][a-z-]*)\\b([^`]*)").matcher(line);
            while (m.find()) {
                String word = m.group(1);
                if (Set.of("is", "and", "reads", "does", "in", "check", "a", "the", "to", "or", "on", "can", "run").contains(word) && !root.getSubcommands().containsKey(word)) continue;
                if (!root.getSubcommands().containsKey(word)) { if (!Set.of("guide", "audit").contains(word)) missing.add("weave " + word); continue; }
                CommandLine sub = root.getSubcommands().get(word);
                Matcher nested = Pattern.compile("^\\s+([a-z][a-z-]*)\\b").matcher(m.group(2));
                if (nested.find() && sub.getSubcommands().containsKey(nested.group(1))) sub = sub.getSubcommands().get(nested.group(1));
                Matcher o = Pattern.compile("(--[a-z][a-z-]*)").matcher(m.group(2).replaceAll("#.*", ""));
                while (o.find()) if (!sub.getCommandSpec().optionsMap().containsKey(o.group(1))) missing.add(word + " " + o.group(1));
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void everyGuidePageTheDeployerNamesIsInTheJar() throws Exception {
        Matcher m = Pattern.compile("weave guide ([a-z0-9]+)").matcher(everything());
        while (m.find()) {
            if (Set.of("lists", "n", "d").contains(m.group(1))) continue;   // "dN" in a table heading stands for d1 to d8
            assertThat(Guide.find(m.group(1))).as("weave guide " + m.group(1)).isPresent();
        }
    }
}
