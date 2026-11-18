package io.github.llm4j.loom.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The documentation the Guide reads, shipped in the jar so it works with no repository and no network: the guide and the
 * language reference ({@code guide/}), the tools catalog ({@code catalog/}) and the library ({@code library/}: the wikis,
 * the tools' pages, security, evaluation and the modules' READMEs). The Guide searches it, reads a page's headings and reads
 * one section, each bounded, before it explains or recommends a feature.
 */
final class GuideLibrary {

    /** The folders of the jar that hold pages the Guide may read. */
    static final List<String> ROOTS = List.of("guide/", "catalog/", "library/", "skill-patterns/");

    static final int MAX_HITS = 8;
    static final int MAX_SECTION_CHARS = 8000;
    private static final int SNIPPET = 220;
    private static final int MIN_WORD = 3;

    /** One section of a page: the heading line's text (empty before the first heading) and the lines under it. */
    record Section(String page, String heading, int level, String body) {}

    /** One search result. */
    record Hit(String page, String heading, int score, String snippet) {}

    private GuideLibrary() {}

    /** Every page, by its path in the jar, sorted. */
    static List<String> pages() {
        return Holder.PAGES;
    }

    private static final class Holder {
        static final List<String> PAGES = list();
    }

    private static List<String> list() {
        Set<String> out = new java.util.TreeSet<>();
        try {
            Path where = Path.of(GuideLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(where)) {
                for (String root : ROOTS) {
                    Path dir = where.resolve(root);
                    if (!Files.isDirectory(dir)) continue;
                    try (Stream<Path> s = Files.walk(dir)) {
                        s.filter(p -> p.toString().endsWith(".md")).forEach(p -> out.add(where.relativize(p).toString().replace('\\', '/')));
                    }
                }
            } else {
                try (JarFile jar = new JarFile(where.toFile())) {
                    jar.stream()
                            .map(java.util.jar.JarEntry::getName)
                            .filter(n -> n.endsWith(".md") && ROOTS.stream().anyMatch(n::startsWith))
                            .forEach(out::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the documentation in the jar", e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not find where weave runs from", e);
        }
        return List.copyOf(out);
    }

    /** A page's text; empty when there is no such page (only the listed pages can be read). */
    static Optional<String> text(String page) {
        if (!pages().contains(page)) return Optional.empty();
        try (InputStream in = GuideLibrary.class.getResourceAsStream("/" + page)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + page, e);
        }
    }

    /** A page's title: its first heading, else its path. */
    static String title(String page) {
        return text(page).flatMap(t -> t.lines().filter(l -> l.startsWith("#")).findFirst()).map(GuideLibrary::headingText).orElse(page);
    }

    /** A page cut into sections at its headings; headings inside code blocks are not headings. */
    static List<Section> sections(String page) {
        String text = text(page).orElse("");
        List<Section> out = new ArrayList<>();
        String heading = "";
        int level = 0;
        StringBuilder body = new StringBuilder();
        boolean code = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("```")) code = !code;
            if (!code && line.matches("#{1,6} .*")) {
                if (!heading.isEmpty() || !body.toString().isBlank()) out.add(new Section(page, heading, level, body.toString().strip()));
                heading = headingText(line);
                level = line.indexOf(' ');
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        if (!heading.isEmpty() || !body.toString().isBlank()) out.add(new Section(page, heading, level, body.toString().strip()));
        return List.copyOf(out);
    }

    /** The page's headings, indented by level: what the Guide reads before it picks a section. */
    static String outline(String page) {
        StringBuilder b = new StringBuilder(page).append('\n');
        for (Section s : sections(page)) if (!s.heading().isEmpty()) b.append("  ".repeat(Math.max(0, s.level() - 1))).append(s.heading()).append('\n');
        return b.toString().strip();
    }

    /** One section, by its heading (exact, ignoring case and leading emoji), with its subsections, cut at the bound. */
    static Optional<String> section(String page, String heading) {
        List<Section> all = sections(page);
        for (int i = 0; i < all.size(); i++) {
            Section s = all.get(i);
            if (!s.heading().equalsIgnoreCase(heading.strip())) continue;
            StringBuilder b = new StringBuilder("#".repeat(Math.max(1, s.level()))).append(' ').append(s.heading()).append('\n').append(s.body()).append('\n');
            for (int j = i + 1; j < all.size() && all.get(j).level() > s.level(); j++)
                b.append('\n').append("#".repeat(all.get(j).level())).append(' ').append(all.get(j).heading()).append('\n').append(all.get(j).body()).append('\n');
            String t = b.toString().strip();
            return Optional.of(t.length() <= MAX_SECTION_CHARS ? t : t.substring(0, MAX_SECTION_CHARS) + "\n… (cut: read a smaller section)");
        }
        return Optional.empty();
    }

    /** The sections that best match the words, best first: a word in the heading counts more than one in the text. */
    static List<Hit> search(String query) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) if (w.length() >= MIN_WORD) words.add(w);
        if (words.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>();
        for (String page : pages())
            for (Section s : sections(page)) {
                String head = s.heading().toLowerCase(Locale.ROOT);
                String body = s.body().toLowerCase(Locale.ROOT);
                int score = 0;
                int matched = 0;
                for (String w : words) {
                    boolean inHead = head.contains(w);
                    boolean inBody = body.contains(w);
                    if (inHead || inBody) matched++;
                    score += (inHead ? 3 : 0) + (inBody ? 1 : 0);
                }
                if (matched == 0) continue;
                // every word found matters more than one word found often
                score += matched * 4;
                hits.add(new Hit(page, s.heading(), score, snippet(s.body())));
            }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::page));
        return List.copyOf(hits.subList(0, Math.min(MAX_HITS, hits.size())));
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET ? flat : flat.substring(0, SNIPPET) + "…";
    }

    private static String headingText(String line) {
        return line.replaceFirst("^#+\\s*", "").replaceAll("^[^\\p{L}\\p{N}\"`*]+\\s*", "").strip();
    }
}
