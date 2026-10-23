package io.github.llm4j.loom.review.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of {@code packs =} in {@code review.properties}: a pack name, the version it must be
 * and, optionally, the hash its files must have. {@code core@1} accepts any 1.x.y; {@code
 * acme@2.0.1 sha256:9c1e…} accepts exactly that version with exactly those files.
 *
 * @param name the pack's name
 * @param version {@code major}, {@code major.minor} or {@code major.minor.patch}
 * @param sha256 the required hash, or empty
 */
public record PackPin(String name, String version, Optional<String> sha256) {

    private static final Pattern ENTRY =
            Pattern.compile(
                    "([a-z][a-z0-9-]*)@(\\d+(?:\\.\\d+){0,2})(?:\\s+sha256:([0-9a-fA-F]{64}))?");

    /** Parses the whole value; every bad entry is reported. */
    public static List<PackPin> parseAll(String value) {
        List<PackPin> pins = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String raw : value.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty()) continue;
            Matcher m = ENTRY.matcher(entry);
            if (!m.matches()) {
                problems.add(
                        "'"
                                + entry
                                + "' is not a pack pin: write name@version, such as core@1 or acme@2.0.1 sha256:<64 hex digits>");
                continue;
            }
            pins.add(
                    new PackPin(
                            m.group(1),
                            m.group(2),
                            Optional.ofNullable(m.group(3)).map(String::toLowerCase)));
        }
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        return pins;
    }

    /** True when {@code actual} ({@code major.minor.patch}) is what the pin asks for. */
    public boolean accepts(String actual) {
        return actual.equals(version) || actual.startsWith(version + ".");
    }

    @Override
    public String toString() {
        return name + "@" + version + sha256.map(h -> " sha256:" + h).orElse("");
    }
}
