package io.github.llm4j.loom.starters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.cli.WeaveCLI;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.review.Reviews;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 29: docs/REVIEWABLE_ARCHITECTURE.md cannot drift from the code. Every {@code loom} block
 * loads; every {@code text check} and {@code text review RULE} block is the output {@code weave}
 * prints now for the {@code loom} block before it (run with {@code
 * -Dregenerate.architecture.page=true} to rewrite them); every rule id the page names exists;
 * every probe link resolves to a section of the probes README; and a framework is named only in a
 * sentence that links a probe.
 */
class ReviewableArchitectureDocsTest {

    private static final Path PAGE = Path.of("../../../docs/REVIEWABLE_ARCHITECTURE.md").toAbsolutePath().normalize();
    private static final Path PROBES_README = Path.of("../../../probes/README.md").toAbsolutePath().normalize();
    private static final Pattern FENCE = Pattern.compile("```([^\\n]*)\\n(.*?)```", Pattern.DOTALL);

    @TempDir Path dir;

    private record Block(String info, String body, int start, int end) {}

    private static List<Block> blocks(String markdown) {
        List<Block> out = new ArrayList<>();
        Matcher m = FENCE.matcher(markdown);
        while (m.find()) out.add(new Block(m.group(1).strip(), m.group(2), m.start(2), m.end(2)));
        return out;
    }

    // ── running weave on a block ─────────────────────────────────────────────────────────────

    private String run(String script, boolean bare, String... args) throws Exception {
        Path work = Files.createTempDirectory(dir, "ex");
        Files.writeString(work.resolve("main.loom"), script);
        // LC07 guesses from the wording of a prompt, so it runs only with heuristics on
        Files.writeString(work.resolve("review.properties"), "heuristics = on\n");
        Files.createDirectories(work.resolve("policies"));
        Files.writeString(work.resolve("policies/house-rules.md"), "## no_refund_promise\nThe reply promises a refund, a credit or any compensation.\n");
        if (!bare) {
            Files.createDirectories(work.resolve("eval/golden"));
            Files.writeString(
                    work.resolve("eval/golden/compliance.yaml"),
                    "- id: c-1\n  policy: HouseRules\n  text: \"We will refund you in full.\"\n  expected: { no_refund_promise: violated }\n"
                            + "- id: c-2\n  policy: HouseRules\n  near_miss: true\n  text: \"I cannot promise a refund, but I have asked a colleague.\"\n  expected: { no_refund_promise: clean }\n");
            Files.writeString(
                    work.resolve("eval/golden/invoice.yaml"),
                    "- id: i-1\n  input: \"Acme, total 240\"\n  expected_fields: { vendor: Acme, total: 240 }\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        PrintStream capture = new PrintStream(out, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
        try {
            List<String> all = new ArrayList<>(List.of(args));
            all.add(1, work.resolve("main.loom").toString());
            WeaveCLI.run(all.toArray(new String[0]));
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** What the page shows of {@code weave check}: the verdict lines, without what depends on the machine. */
    private String check(String script, boolean bare) throws Exception {
        StringBuilder b = new StringBuilder();
        for (String line : run(script, bare, "check", "--no-env").split("\\R")) {
            if (line.startsWith("ℹ not set yet") || line.startsWith("Picked up") || line.isBlank()) continue;
            b.append(line.replace(dir.toString(), "")).append('\n');
        }
        return b.toString();
    }

    /** What the page shows of {@code weave review}: the finding of each named rule. */
    private String review(String script, boolean bare, List<String> rules) throws Exception {
        String[] lines = run(script, bare, "review", "--min-severity", "info").split("\\R");
        StringBuilder b = new StringBuilder();
        for (String rule : rules) {
            boolean inside = false;
            for (String line : lines) {
                if (line.matches("^(HIGH|MEDIUM|LOW|INFO)\\s+" + rule + "\\b.*")) {
                    inside = true;
                    b.append(line).append('\n');
                } else if (inside && line.startsWith("        ")) {
                    if (!line.strip().startsWith("auto-fix") && !line.strip().startsWith("options:")) b.append(line).append('\n');
                } else if (inside) {
                    inside = false;
                }
            }
        }
        return b.toString();
    }

    private String expected(Block loom, Block output) throws Exception {
        String[] words = output.info().split("\\s+");
        if (output.info().startsWith("mermaid graph")) {
            StringBuilder g = new StringBuilder();
            for (String line : run(loom.body(), true, "graph", "--workflow", "Main", "--format", "mermaid").split("\\R"))
                if (!line.startsWith("Picked up") && !line.startsWith("%%") && !line.isBlank()) g.append(line).append('\n');
            return g.toString();
        }
        boolean bare = List.of(words).contains("bare");
        if (words[1].equals("check")) return check(loom.body(), bare);
        List<String> rules = new ArrayList<>();
        for (int i = 2; i < words.length; i++) if (!words[i].equals("bare")) rules.add(words[i]);
        return review(loom.body(), bare, rules);
    }

    private static boolean isOutput(Block b) {
        return b.info().startsWith("text check") || b.info().startsWith("text review") || b.info().startsWith("mermaid graph");
    }

    // ── the tests ────────────────────────────────────────────────────────────────────────────

    @Test
    void everyLoomBlockLoadsAndEveryFixedBlockIsCleanOfWhatItFixes() throws Exception {
        String page = Files.readString(PAGE);
        int loaded = 0;
        for (Block b : blocks(page)) {
            if (!b.info().startsWith("loom")) continue;
            Path f = Files.writeString(Files.createTempFile(dir, "block", ".loom"), b.body());
            assertThat(new LoomLoader().load(f.toString())).as("block at " + b.start()).isNotNull();
            loaded++;
            String[] words = b.info().split("\\s+");
            if (words.length >= 3 && words[1].equals("clean")) {
                assertThat(check(b.body(), false)).as("weave check of the fixed block at " + b.start()).contains("ready to run");
                if (!words[2].equals("-")) {
                    List<String> rules = List.of(words[2].split(","));
                    assertThat(review(b.body(), false, rules)).as("the fixed block at " + b.start() + " is clean of " + rules).isEmpty();
                }
            }
        }
        assertThat(loaded).isGreaterThanOrEqualTo(19);
    }

    @Test
    void everyOutputOnThePageIsWhatWeavePrintsNow() throws Exception {
        String page = Files.readString(PAGE);
        List<Block> all = blocks(page);
        boolean regenerate = Boolean.getBoolean("regenerate.architecture.page");
        StringBuilder rewritten = new StringBuilder();
        int cursor = 0;
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            Block b = all.get(i);
            if (!isOutput(b)) continue;
            Block loom = null;
            for (int j = i - 1; j >= 0 && loom == null; j--) if (all.get(j).info().startsWith("loom")) loom = all.get(j);
            assertThat(loom).as("a loom block before the output at " + b.start()).isNotNull();
            String now = expected(loom, b);
            assertThat(now).as("weave printed nothing for the block at " + loom.start()).isNotBlank();
            checked++;
            if (regenerate) {
                rewritten.append(page, cursor, b.start()).append(now);
                cursor = b.end();
            } else {
                assertThat(b.body()).as("the output block at " + b.start() + " (rerun with -Dregenerate.architecture.page=true)").isEqualTo(now);
            }
        }
        if (regenerate) {
            rewritten.append(page.substring(cursor));
            Files.writeString(PAGE, rewritten.toString());
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
    }

    @Test
    void everyRuleIdThePageNamesIsInTheCatalogue() throws Exception {
        Set<String> ids = new java.util.HashSet<>();
        Reviews.catalog().all().forEach(r -> ids.add(r.id()));
        ids.addAll(auditIds());
        Matcher m = Pattern.compile("\\bL[A-Z]\\d{2}\\b").matcher(Files.readString(PAGE));
        int named = 0;
        while (m.find()) {
            named++;
            assertThat(ids).as(m.group() + " on the page").contains(m.group());
        }
        assertThat(named).isGreaterThanOrEqualTo(20);
    }

    /** The security audit's rule ids are listed in docs/security/weave-audit.md (LA01 .. LA22). */
    private static Set<String> auditIds() throws Exception {
        Set<String> ids = new java.util.HashSet<>();
        Matcher m = Pattern.compile("\\| (LA\\d{2}) \\|").matcher(Files.readString(Path.of("../../../docs/security/weave-audit.md")));
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    @Test
    void everyProbeLinkResolvesToASectionOfTheProbesReadme() throws Exception {
        String readme = Files.readString(PROBES_README);
        Set<String> anchors = new java.util.HashSet<>();
        for (String line : readme.split("\\R"))
            if (line.startsWith("#")) anchors.add(anchor(line.replaceFirst("^#+\\s*", "")));
        Matcher m = Pattern.compile("probes/README\\.md#([a-z0-9-]+)").matcher(Files.readString(PAGE));
        Set<String> linked = new java.util.HashSet<>();
        while (m.find()) {
            linked.add(m.group(1));
            assertThat(anchors).as("probe link " + m.group(1)).contains(m.group(1));
        }
        // each of the nine new probes (P9 to P17) is linked from the page
        for (int p = 9; p <= 17; p++) {
            String prefix = "p" + p + "-";
            assertThat(linked).as("a link to probe P" + p).anyMatch(a -> a.startsWith(prefix));
        }
    }

    private static String anchor(String heading) {
        return heading.toLowerCase().replaceAll("[^a-z0-9 -]", "").replace(' ', '-');
    }

    @Test
    void aFrameworkIsNamedOnlyInASentenceThatLinksAProbe() throws Exception {
        String page = Files.readString(PAGE);
        String prose = FENCE.matcher(page).replaceAll("");
        for (String paragraph : prose.split("\\n\\s*\\n")) {
            for (String row : paragraph.split("\\n")) {
                if (!row.matches("(?is).*\\b(LangGraph|LangChain|Google ADK|ADK)\\b.*")) continue;
                if (row.startsWith("#")) continue;
                assertThat(row).as("a sentence that names a framework must link a probe").contains("probes/README.md#");
            }
        }
    }

    @Test
    void thePageStatesTheClaimAndWhatIsNotEnforced() throws Exception {
        String page = Files.readString(PAGE);
        assertThat(page)
                .contains("Loom distils multi-agent best practice into a language that can be reviewed, by people and by tools")
                .contains("In code-first frameworks the architecture is a convention; in Loom it is checked")
                .contains("What is not enforced")
                .contains("A declared effect is the developer's word");
    }

    // ── the article (R7) ─────────────────────────────────────────────────────────────────────

    private static final Path ARTICLE = Path.of("../../../docs/articles/REVIEWABLE_ARCHITECTURE_ARTICLE.md").toAbsolutePath().normalize();

    @Test
    void theArticleLinksThePageAndOnlyProbeSectionsThatExist() throws Exception {
        String article = Files.readString(ARTICLE);
        assertThat(article).contains("(../REVIEWABLE_ARCHITECTURE.md)").contains("Everything here is on that page");
        Set<String> anchors = new java.util.HashSet<>();
        for (String line : Files.readString(PROBES_README).split("\\R"))
            if (line.startsWith("#")) anchors.add(anchor(line.replaceFirst("^#+\\s*", "")));
        Matcher m = Pattern.compile("probes/README\\.md#([a-z0-9-]+)").matcher(article);
        Set<String> linked = new java.util.HashSet<>();
        while (m.find()) {
            linked.add(m.group(1));
            assertThat(anchors).as(m.group(1)).contains(m.group(1));
        }
        for (int p = 9; p <= 17; p++) {
            String prefix = "p" + p + "-";
            assertThat(linked).as("the article links probe P" + p).anyMatch(a -> a.startsWith(prefix));
        }
    }

    @Test
    void theArticleSaysNothingAboutAFrameworkOutsideAProbeSentenceAndNamesNoRuleThePageDoesNot() throws Exception {
        String article = Files.readString(ARTICLE);
        for (String line : article.split("\\R"))
            if (line.matches("(?is).*\\b(LangGraph|LangChain|Google ADK|ADK)\\b.*"))
                assertThat(line).as("framework sentence").contains("probes/README.md#");
        String page = Files.readString(PAGE);
        Matcher m = Pattern.compile("\\bL[A-Z]\\d{2}\\b").matcher(article);
        while (m.find()) assertThat(page).as(m.group() + " is on the page").contains(m.group());
    }

    @Test
    void theArticleIsInTheDocsIndex() throws Exception {
        assertThat(Files.readString(Path.of("../../../docs/README.md"))).contains("articles/REVIEWABLE_ARCHITECTURE_ARTICLE.md");
    }
}
