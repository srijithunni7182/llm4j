package io.github.llm4j.loom.init;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The last published release, which is what a generated project or a deployment must depend on.
 * A development build is a {@code -SNAPSHOT} of the next version, and nobody can download that, so
 * anything that names a version for someone else to fetch asks here. The value comes from the
 * {@code llm4j.released} property of the parent pom, filtered into {@code released.properties} when
 * the module is built.
 */
public final class ReleasedVersion {
    private static final String RESOURCE = "/released.properties";

    private static final String VERSION = read();

    private ReleasedVersion() {}

    /** The version of the last published release, for example {@code 6.1.0}. */
    public static String get() {
        return VERSION;
    }

    private static String read() {
        try (InputStream in = ReleasedVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null)
                throw new IllegalStateException(
                        RESOURCE + " is missing from the jar: rebuild with Maven");
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("released", "").strip();
            if (v.isEmpty() || v.contains("@"))
                throw new IllegalStateException(
                        RESOURCE + " was not filled in by the build (released=" + v + ")");
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }
}
