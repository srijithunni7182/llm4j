package io.github.llm4j.loom.cli;

import io.github.llm4j.a2a.AgentCardBuilder;
import io.github.llm4j.loom.ast.ExposeDef;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.host.ExposedCard;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code weave card}: the agent card an {@code expose} block shows to outsiders (A2A), so a
 * reviewer sees in a diff what a change does to what the world can see. Nothing is run and nothing
 * is written: no model, no secret and no network is touched.
 */
@Command(
        name = "card",
        description =
                "Prints the A2A agent card of an expose block as JSON, without running anything, so a change to what outsiders see shows in a diff.")
final class CardCommand implements Callable<Integer> {

    static final String PLACEHOLDER_ADDRESS = "https://your-agent.example.com/a2a";

    @Parameters(index = "0", description = "The .loom script.")
    File script;

    @Option(
            names = "--address",
            description = "The https address the agent will be served at (default: a placeholder).")
    String address;

    @Option(names = "--expose", description = "Only this expose block (default: the only one).")
    String expose;

    @Override
    public Integer call() {
        return card(this, WeaveEnv.system());
    }

    static int card(CardCommand c, WeaveEnv env) {
        LoomScript loaded;
        try {
            loaded = new LoomLoader().load(c.script.toString());
        } catch (java.io.IOException | RuntimeException e) {
            env.err().println("Error: " + (e.getMessage() == null ? e : e.getMessage()));
            return 2;
        }
        List<ExposeDef> exposes = loaded.getExposes();
        if (exposes.isEmpty()) {
            env.err().println("Error: the script has no expose block, so it shows no card.");
            return 2;
        }
        ExposeDef chosen;
        if (c.expose != null) {
            chosen = exposes.stream().filter(e -> e.getName().equals(c.expose)).findFirst().orElse(null);
            if (chosen == null) {
                env.err()
                        .println(
                                "Error: no expose block named "
                                        + c.expose
                                        + ". Known: "
                                        + String.join(", ", exposes.stream().map(ExposeDef::getName).toList())
                                        + ".");
                return 2;
            }
        } else if (exposes.size() == 1) {
            chosen = exposes.get(0);
        } else {
            env.err()
                    .println(
                            "Error: the script has several expose blocks; choose one with --expose. Known: "
                                    + String.join(", ", exposes.stream().map(ExposeDef::getName).toList())
                                    + ".");
            return 2;
        }
        try {
            AgentCardBuilder.Published card =
                    ExposedCard.of(chosen, loaded, c.address == null ? PLACEHOLDER_ADDRESS : c.address);
            env.out().println(card.json());
            return 0;
        } catch (IllegalArgumentException e) {
            env.err().println("Error: " + e.getMessage());
            return 2;
        }
    }
}
