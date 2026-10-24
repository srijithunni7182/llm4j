package io.github.llm4j.loom.overlay;

import io.github.llm4j.loom.graph.GraphResult;
import io.github.llm4j.loom.graph.GraphService;
import io.github.llm4j.loom.travel.ObserveReplay;
import io.github.llm4j.loom.travel.TravelFixtures;
import java.nio.file.Path;
import java.util.Set;

/** A case of the travel corpus, run for real and read back as an overlay run. */
final class OverlayFixtures {

    private OverlayFixtures() {}

    record Built(TravelFixtures.Run real, GraphResult graph, RunOverlay.Run run) {}

    static Built build(Path dir, String name) throws Exception {
        var c =
                TravelFixtures.CORPUS.stream()
                        .filter(x -> x.name().equals(name))
                        .findFirst()
                        .orElseThrow();
        TravelFixtures.Run real = TravelFixtures.run(dir, c);
        return build(real, name);
    }

    static Built build(TravelFixtures.Run real, String id) throws Exception {
        ObserveReplay.Result r =
                ObserveReplay.run(
                        new ObserveReplay.Options(
                                real.script(),
                                real.source().workflow(),
                                real.source().input(),
                                Set.of()),
                        real.run().journal());
        return new Built(real, new GraphService().graph(real.script()), RunOverlays.of(id, r));
    }
}
