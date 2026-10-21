package io.github.llm4j.loom.context;

import io.github.llm4j.context.ContextWindow;
import io.github.llm4j.context.ContextWindows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The context windows of models, from several files layered by priority. A model's window belongs
 * to the model, not to a script, so it is configured outside: files given on the command line (the
 * last one wins), then the file beside the script, then the user's file, then the seeded catalogue.
 */
public final class WindowLayers {

    /** Where a window came from, for {@code weave audit}. */
    public static final String CATALOGUE = "the seeded catalogue";

    /** One file (or the catalogue) and what it says. */
    public record Layer(String source, ContextWindows windows) {}

    /** A window and the layer it came from. */
    public record Found(ContextWindow window, String source) {}

    private final List<Layer> layers; // highest priority first

    private WindowLayers(List<Layer> layers) {
        this.layers = List.copyOf(layers);
    }

    /** Only the catalogue shipped with ai-agent4j. */
    public static WindowLayers catalogue() {
        return new WindowLayers(List.of(new Layer(CATALOGUE, ContextWindows.defaults())));
    }

    /** Nothing at all: no model has a known window. */
    public static WindowLayers none() {
        return new WindowLayers(List.of());
    }

    /** These layers, highest priority first. */
    public static WindowLayers of(List<Layer> highestFirst) {
        return new WindowLayers(highestFirst);
    }

    /** The same layers with one more on top. */
    public WindowLayers over(String source, ContextWindows windows) {
        List<Layer> all = new ArrayList<>();
        all.add(new Layer(source, windows));
        all.addAll(layers);
        return new WindowLayers(all);
    }

    /**
     * The layers for a deployment. {@code commandLine} files must exist (later ones win over
     * earlier); {@code beside} and {@code user} are used only when they are there. A file that
     * cannot be read is an {@link IllegalArgumentException} naming it, with the line.
     */
    public static WindowLayers load(List<Path> commandLine, Path beside, Path user) {
        List<Layer> all = new ArrayList<>();
        for (int i = commandLine.size() - 1; i >= 0; i--) all.add(read(commandLine.get(i), true));
        if (beside != null && Files.isRegularFile(beside)) all.add(read(beside, false));
        if (user != null && Files.isRegularFile(user)) all.add(read(user, false));
        all.add(new Layer(CATALOGUE, ContextWindows.defaults()));
        return new WindowLayers(all);
    }

    private static Layer read(Path file, boolean mustExist) {
        if (!Files.isRegularFile(file)) {
            if (mustExist)
                throw new IllegalArgumentException("the windows file " + file + " does not exist");
            return new Layer(file.toString(), ContextWindows.empty());
        }
        try {
            return new Layer(file.toString(), ContextWindows.load(file));
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "the windows file " + file + " cannot be read: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the windows file " + file + ": " + e.getMessage());
        }
    }

    /** The window of a model from the highest layer that knows it. */
    public Optional<Found> find(String model) {
        if (model == null) return Optional.empty();
        for (Layer layer : layers) {
            Optional<ContextWindow> w = layer.windows().window(model);
            if (w.isPresent()) return Optional.of(new Found(w.get(), layer.source()));
        }
        return Optional.empty();
    }

    public List<Layer> layers() {
        return layers;
    }
}
