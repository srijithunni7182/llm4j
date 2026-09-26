package io.github.llm4j.getviral.web;

import io.github.llm4j.getviral.studio.StudioRun;
import java.util.List;
import java.util.Map;

/** Renders a finished run as a Markdown content pack the creator can paste anywhere. */
public final class PackMarkdown {

    private PackMarkdown() { }

    public static String render(StudioRun run) {
        Map<?, ?> pack = run.events().stream()
                .filter(e -> "pack".equals(e.get("type")))
                .map(e -> (Map<?, ?>) e.get("data"))
                .reduce((a, b) -> b)
                .orElse(Map.of());
        StringBuilder md = new StringBuilder("# GetViral pack — ").append(run.brief().get("idea")).append("\n\n");
        md.append("**Hook:** ").append(pack.get("hook")).append("\n\n");

        if (pack.get("x") instanceof Map<?, ?> x) {
            md.append("## X thread\n\n");
            if (x.get("thread") instanceof List<?> thread) thread.forEach(t -> md.append("> ").append(t).append("\n>\n"));
            md.append("\n**Standalone post:** ").append(x.get("standalone")).append("\n\n");
            md.append("**Reply bait:** ").append(x.get("reply_bait")).append("\n\n");
        }
        if (pack.get("reel") instanceof Map<?, ?> reel) {
            md.append("## Instagram Reel — ").append(reel.get("title")).append(" (").append(reel.get("duration")).append(")\n\n");
            md.append("| Time | Shot | Voiceover | On screen |\n|---|---|---|---|\n");
            if (reel.get("beats") instanceof List<?> beats) {
                for (Object b : beats) {
                    if (b instanceof Map<?, ?> beat) {
                        md.append("| ").append(beat.get("time")).append(" | ").append(beat.get("shot")).append(" | ")
                          .append(beat.get("voiceover")).append(" | ").append(beat.get("on_screen")).append(" |\n");
                    }
                }
            }
            md.append("\n**Audio:** ").append(reel.get("audio")).append("  \n**Cover:** ").append(reel.get("cover_text"));
            md.append("\n\n**Caption:**\n\n").append(reel.get("caption")).append("\n\n").append(join(reel.get("hashtags"))).append("\n\n");
        }
        if (pack.get("youtube") instanceof Map<?, ?> yt) {
            md.append("## YouTube\n\n**Title options:**\n");
            if (yt.get("titles") instanceof List<?> titles) titles.forEach(t -> md.append("- ").append(t).append('\n'));
            md.append("\n**Thumbnail:** ").append(yt.get("thumbnail_concept")).append(" — text: *")
              .append(yt.get("thumbnail_text")).append("*\n\n**Hook script:** ").append(yt.get("hook_script"));
            md.append("\n\n**Description:**\n\n").append(yt.get("description")).append("\n\n");
            if (yt.get("chapters") instanceof List<?> chapters) chapters.forEach(c -> md.append(c).append('\n'));
            md.append("\n**Shorts cut:** ").append(yt.get("shorts_cut")).append("\n\n**Tags:** ").append(join(yt.get("tags"))).append('\n');
        }
        if (pack.get("critic") instanceof Map<?, ?> critic) {
            md.append("\n---\nCritic score: **").append(critic.get("score")).append("/10** — ").append(critic.get("headline")).append('\n');
        }
        return md.toString();
    }

    private static String join(Object list) {
        return list instanceof List<?> l ? String.join(" ", l.stream().map(String::valueOf).toList()) : "";
    }
}
