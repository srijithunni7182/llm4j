package io.github.llm4j.getviral.engine;

import java.util.Collection;
import java.util.List;

/**
 * A deck of distinct visual styles for the ArtDirector. Like {@link CreativeLenses}, each run is
 * dealt styles this creator hasn't used yet, so the look of their packs keeps evolving.
 */
public final class VisualStyles {

    public static final List<String> ALL = List.of(
            "cinematic golden-hour editorial photo, shallow depth of field, 35mm",
            "high-contrast film noir, hard shadows, black and white with one accent colour",
            "risograph print, grainy two-colour overprint, pink and teal",
            "claymation still, handmade plasticine textures, soft studio light",
            "isometric 3D diorama, clean pastel materials, tilt-shift",
            "symmetrical pastel set design, centred framing, storybook palette",
            "gritty documentary handheld, available light, candid moment",
            "neon night city, wet reflections, magenta and cyan glow",
            "overhead flat-lay, bold solid-colour background, graphic shadows",
            "watercolour illustration, loose washes, paper texture",
            "cut-paper collage zine, torn edges, halftone photos",
            "macro close-up, extreme detail, dark moody background",
            "retro 1970s film stock, warm fade, soft grain",
            "Y2K chrome and gradients, glossy 3D type-friendly space",
            "minimal Scandinavian daylight, white space, muted earth tones",
            "comic-book panel, bold ink lines, flat vivid colours",
            "vaporwave sunset, pastel grid, surreal props",
            "bright pop-art, Ben-Day dots, primary colours",
            "moody chiaroscuro painting, Rembrandt lighting",
            "clean product-studio shot, seamless backdrop, crisp rim light");

    private VisualStyles() { }

    public static List<String> deal(Collection<String> used, int count, long seed) {
        return CreativeLenses.dealFrom(ALL, used, count, seed);
    }
}
