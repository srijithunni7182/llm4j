package io.github.llm4j.loom.review.pack;

import io.github.llm4j.loom.review.rule.RuleDoc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The rule packs in force for one review: the core pack that ships with weave, then the packs a
 * project names in {@code packs =}, each found in {@code review-rules/<name>/} beside the script
 * and each checked against its pin. Nothing is looked up anywhere else and nothing is downloaded.
 *
 * <p>It is strict on purpose. A pack that is in the folder but not named, one that is named but
 * missing, a version or hash that differs from the pin, and a rule id used by two packs are all
 * refused, with every such problem listed, so the rules that run are always the rules that were
 * reviewed.
 */
public final class PackSet {

    /** The folder, beside the script, that holds a project's packs. */
    public static final String FOLDER = "review-rules";

    private final List<Pack> packs;

    private PackSet(List<Pack> packs) {
        this.packs = List.copyOf(packs);
    }

    /** Core first, then project packs in the order the project named them. */
    public List<Pack> packs() {
        return packs;
    }

    /** Only the core pack, for a project that declares nothing. */
    public static PackSet coreOnly(Pack core) {
        return new PackSet(List.of(core));
    }

    /**
     * Resolves {@code packs =}.
     *
     * @param pins the value of {@code packs}, or empty when the project did not declare any
     * @param base the folder that holds {@code review-rules/}
     * @param core the bundled pack
     * @throws PackException listing every problem
     */
    public static PackSet resolve(Optional<String> pins, Path base, Pack core) {
        List<String> problems = new ArrayList<>();
        List<PackPin> declared = List.of();
        if (pins.isPresent()) {
            try {
                declared = PackPin.parseAll(pins.get());
            } catch (IllegalArgumentException e) {
                throw new PackException("packs", List.of(e.getMessage()));
            }
        }
        List<Pack> packs = new ArrayList<>();
        packs.add(core);
        List<String> named = new ArrayList<>();
        for (PackPin pin : declared) {
            named.add(pin.name());
            if (pin.name().equals(PackManifest.CORE)) {
                check(pin, core, problems);
                continue;
            }
            Path dir = base.resolve(FOLDER).resolve(pin.name());
            if (!Files.isDirectory(dir)) {
                problems.add(
                        pin
                                + ": there is no folder "
                                + FOLDER
                                + "/"
                                + pin.name()
                                + " beside the script. Packs are never downloaded: copy the pack there (weave rules add).");
                continue;
            }
            try {
                Pack p = PackLoader.directory(dir);
                if (!p.manifest().name().equals(pin.name()))
                    problems.add(
                            pin
                                    + ": the folder holds a pack called "
                                    + p.manifest().name()
                                    + ", not "
                                    + pin.name());
                else if (check(pin, p, problems)) packs.add(p);
            } catch (PackException e) {
                for (String pr : e.problems()) problems.add(pin.name() + ": " + pr);
            }
        }
        for (String dir : packFolders(base))
            if (!named.contains(dir))
                problems.add(
                        FOLDER
                                + "/"
                                + dir
                                + " is a rule pack that is not listed in packs=. Add '"
                                + dir
                                + "@<version>' to review.properties, or remove the folder.");
        problems.addAll(duplicates(packs));
        if (!problems.isEmpty()) throw new PackException("packs", problems);
        return new PackSet(packs);
    }

    private static boolean check(PackPin pin, Pack pack, List<String> problems) {
        boolean ok = true;
        if (!pin.accepts(pack.manifest().version())) {
            problems.add(
                    pin
                            + ": the pack is version "
                            + pack.manifest().version()
                            + ", which is not "
                            + pin.version()
                            + ". Update the pin once you have read what changed.");
            ok = false;
        }
        if (pin.sha256().isPresent()) {
            String actual = PackHash.of(pack.files());
            if (!actual.equals(pin.sha256().get())) {
                problems.add(
                        pin.name()
                                + ": the files of the pack hash to sha256:"
                                + actual
                                + " but the pin expects sha256:"
                                + pin.sha256().get()
                                + ". The pack changed since it was pinned.");
                ok = false;
            }
        }
        return ok;
    }

    private static List<String> packFolders(Path base) {
        Path root = base.resolve(FOLDER);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve("pack.yaml")))
                    .map(d -> d.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> duplicates(List<Pack> packs) {
        List<String> problems = new ArrayList<>();
        Map<String, String> owner = new HashMap<>();
        for (Pack p : packs)
            for (RuleDoc r : p.rules()) {
                String first = owner.putIfAbsent(r.id(), p.label());
                if (first != null)
                    problems.add(
                            r.id()
                                    + " is defined by both "
                                    + first
                                    + " and "
                                    + p.label()
                                    + ". A pack cannot redefine another pack's rule.");
            }
        return problems;
    }
}
