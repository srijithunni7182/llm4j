package io.github.llm4j.loom.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave guide-facts}: the facts the Guide may state about llm4j, each with what it does
 * not do, and the moments that bring them up.
 */
@Command(
        name = "guide-facts",
        description =
                "Prints, as JSON, the facts the Guide may state about llm4j (what each feature is for, the problem it solves, the command that shows it, what it does not do, and its page), or one fact by id.")
final class GuideFactsCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<fact>", description = "One fact by id (default: all).")
    String id;

    @Option(names = "--moments", description = "Also list the moments that bring each fact up.")
    boolean moments;

    @Option(
            names = "--catalog",
            paramLabel = "list|<tool>",
            description = "Print the tools catalog instead: list for every ready-made tool in one line, or one tool's section (http, openapi, mcp, sandbox, ...).")
    String catalog;

    @Option(names = "--feature", paramLabel = "list|<id>", description = "What Loom does as a workflow language: list for every shape and feature with the words that call for it, or one feature in full.")
    String feature;

    @Option(names = "--search", paramLabel = "<words>", description = "Search the whole shipped documentation and list the best sections.")
    String search;

    @Option(names = "--page", paramLabel = "<path>", description = "A page of the shipped documentation: its headings, or with --section one section.")
    String page;

    @Option(names = "--section", paramLabel = "<heading>", description = "With --page: the section under this heading.")
    String section;

    @Override
    public Integer call() {
        if (feature != null) return feature(feature, WeaveEnv.system());
        if (catalog != null) return catalog(catalog, WeaveEnv.system());
        if (search != null || page != null) return docs(search, page, section, WeaveEnv.system());
        return run(id, moments, WeaveEnv.system());
    }

    static int feature(String id, WeaveEnv env) {
        try {
            env.out().println(GuideMcpServer.feature(id));
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    static int docs(String search, String page, String section, WeaveEnv env) {
        try {
            env.out().println(GuideMcpServer.docs(search == null ? "" : search, page == null ? "" : page, section == null ? "" : section));
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }

    static int catalog(String name, WeaveEnv env) {
        if (name.equals("list")) {
            env.out().println(ToolCatalog.glance());
            return 0;
        }
        var section = ToolCatalog.section(name);
        if (section.isEmpty()) {
            env.err().println("Error: no tool " + name + " in the catalog. The tools are: " + String.join(", ", ToolCatalog.names()) + ".");
            return 2;
        }
        env.out().println(section.get());
        return 0;
    }

    static int run(String id, boolean withMoments, WeaveEnv env) {
        Map<String, GuideCatalogue.Fact> facts = GuideCatalogue.facts();
        if (id != null && !facts.containsKey(id)) {
            env.err().println("Error: no fact " + id + ". The facts are: " + String.join(", ", facts.keySet()) + ".");
            return 2;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>();
        for (GuideCatalogue.Fact f : facts.values()) if (id == null || f.id().equals(id)) list.add(f.json());
        out.put("facts", list);
        if (withMoments) {
            List<Object> ms = new ArrayList<>();
            for (GuideCatalogue.Moment m : GuideCatalogue.moments())
                if (id == null || m.fact().equals(id)) ms.add(m.json(facts.get(m.fact())));
            out.put("moments", ms);
        }
        env.out().println(GuideContextCommand.json(out));
        return 0;
    }
}
