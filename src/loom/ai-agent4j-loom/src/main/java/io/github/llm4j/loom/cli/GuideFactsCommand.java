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

    @Override
    public Integer call() {
        return run(id, moments, WeaveEnv.system());
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
