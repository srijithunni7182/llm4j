package io.github.llm4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * What a jar says it is: its version, whether it is a release or a development build, the commit it
 * was built from and when. Every module jar carries these in its manifest ({@code
 * Implementation-Version}, {@code Build-Kind}, {@code Build-Commit}, {@code Build-Dirty}, {@code
 * Build-Time}), so two jars that share a version number can be told apart without running either.
 *
 * @param version the version number, or "unknown"
 * @param kind {@code release}, {@code development}, or "unknown" for a jar built before the kind
 *     was recorded
 * @param commit the short git commit, or "unknown" (the build ran outside a checkout)
 * @param dirty true when the tree had uncommitted changes
 * @param builtAt when the jar was built (UTC, ISO-8601), or null
 * @param fromClasses true when the code is running from a classes directory, not a jar
 */
public record BuildIdentity(
        String version,
        String kind,
        String commit,
        boolean dirty,
        String builtAt,
        boolean fromClasses) {

    /** The kind recorded for a build that is not a published release. */
    public static final String DEVELOPMENT = "development";

    /** The kind recorded for a published release. */
    public static final String RELEASE = "release";

    private static final String UNKNOWN = "unknown";

    public BuildIdentity {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(commit, "commit");
    }

    /** Reads the identity from a manifest; a missing or unexpanded attribute is "unknown". */
    public static BuildIdentity of(Manifest manifest) {
        Attributes a = manifest.getMainAttributes();
        return new BuildIdentity(
                value(a, "Implementation-Version"),
                value(a, "Build-Kind"),
                value(a, "Build-Commit"),
                "true".equals(a.getValue("Build-Dirty")),
                a.getValue("Build-Time"),
                false);
    }

    /**
     * The identity of the jar that {@code anchor} was loaded from; for classes loaded from a
     * directory (a test, an IDE), an identity with {@link #fromClasses()} set.
     */
    public static BuildIdentity ofClass(Class<?> anchor) {
        URL url = anchor.getResource(anchor.getSimpleName() + ".class");
        if (url == null || !"jar".equals(url.getProtocol()))
            return new BuildIdentity(UNKNOWN, UNKNOWN, UNKNOWN, false, null, true);
        String jar = url.toString().substring(0, url.toString().indexOf("!/") + 2);
        try (InputStream in = new URL(jar + "META-INF/MANIFEST.MF").openStream()) {
            return of(new Manifest(in));
        } catch (IOException e) {
            return new BuildIdentity(UNKNOWN, UNKNOWN, UNKNOWN, false, null, false);
        }
    }

    private static String value(Attributes attributes, String name) {
        String v = attributes.getValue(name);
        // a build outside a git checkout leaves the placeholder unexpanded
        return v == null || v.isBlank() || v.startsWith("${") ? UNKNOWN : v.strip();
    }

    /** True for a build that is not a published release. */
    public boolean isDevelopment() {
        return DEVELOPMENT.equals(kind);
    }

    /**
     * Whether two jars are the same build: the same version and, unless both are releases (whose
     * commit is the tag), the same commit.
     */
    public boolean sameBuildAs(BuildIdentity other) {
        if (!version.equals(other.version)) return false;
        if (RELEASE.equals(kind) && RELEASE.equals(other.kind)) return true;
        return commit.equals(other.commit);
    }

    /**
     * One line a bug report can quote, such as {@code 6.2.0-SNAPSHOT (development, 3d683a35,
     * modified tree, built 2026-10-29T15:30:00Z)}.
     */
    public String describe() {
        if (fromClasses) return "run from classes, not a built jar";
        StringBuilder out = new StringBuilder(version).append(" (");
        out.append(UNKNOWN.equals(kind) ? "build kind unknown" : kind);
        if (!UNKNOWN.equals(commit)) out.append(", ").append(commit);
        if (dirty) out.append(", modified tree");
        if (builtAt != null) out.append(", built ").append(builtAt);
        return out.append(')').toString();
    }
}
