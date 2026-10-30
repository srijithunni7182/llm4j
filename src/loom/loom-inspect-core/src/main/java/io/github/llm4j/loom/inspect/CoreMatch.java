package io.github.llm4j.loom.inspect;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Optional;
import java.util.jar.Manifest;

/**
 * Start-up check that an add-on and the jar it runs against are the same build. An add-on built
 * from one commit against a core from another used to fail at run time with a missing class; this
 * says which two builds met instead. It reads manifests with the JDK only, because the core it
 * checks against may be older than any class this module could ask it for.
 */
public final class CoreMatch {

    private CoreMatch() {}

    /**
     * Refuses to start when {@code addon} and {@code core} are different builds.
     *
     * @param name the add-on's name, for the message
     * @param addonAnchor a class of the add-on
     * @param coreAnchor a class of the jar it runs against
     * @throws IllegalStateException with one sentence naming both builds
     */
    public static void require(String name, Class<?> addonAnchor, Class<?> coreAnchor) {
        Manifest addon = manifestOf(addonAnchor);
        Manifest core = manifestOf(coreAnchor);
        if (addon == null || core == null) return; // classes in a folder: nothing to compare
        mismatch(name, addon, jarName(coreAnchor), core)
                .ifPresent(
                        sentence -> {
                            throw new IllegalStateException(sentence);
                        });
    }

    /** The sentence to refuse with, or empty when the two agree or cannot be compared. */
    public static Optional<String> mismatch(
            String addonName, Manifest addon, String coreName, Manifest core) {
        String av = attribute(addon, "Implementation-Version");
        String cv = attribute(core, "Implementation-Version");
        if (av == null || cv == null) return Optional.empty();
        String ak = attribute(addon, "Build-Kind");
        String ck = attribute(core, "Build-Kind");
        String ac = attribute(addon, "Build-Commit");
        String cc = attribute(core, "Build-Commit");
        boolean bothReleases = "release".equals(ak) && "release".equals(ck);
        boolean same = av.equals(cv) && (bothReleases || ac == null || cc == null || ac.equals(cc));
        if (same) return Optional.empty();
        return Optional.of(
                addonName
                        + " "
                        + describe(av, ak, ac)
                        + " cannot run with "
                        + coreName
                        + " "
                        + describe(cv, ck, cc)
                        + ": they must be the same build. Use the jars from one release, or rebuild"
                        + " both from the same commit (weave --version shows which one you are"
                        + " running).");
    }

    private static String describe(String version, String kind, String commit) {
        StringBuilder out = new StringBuilder(version);
        if (kind != null || commit != null) {
            out.append(" (");
            if (kind != null) out.append(kind);
            if (commit != null) out.append(kind != null ? ", " : "").append(commit);
            out.append(')');
        }
        return out.toString();
    }

    private static String attribute(Manifest m, String name) {
        String v = m.getMainAttributes().getValue(name);
        return v == null || v.isBlank() || v.startsWith("${") ? null : v.strip();
    }

    private static String jarName(Class<?> anchor) {
        URL url = anchor.getResource(anchor.getSimpleName() + ".class");
        if (url == null) return anchor.getName();
        String s = url.toString();
        int bang = s.indexOf("!/");
        if (bang < 0) return anchor.getName();
        String path = s.substring(0, bang);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Manifest manifestOf(Class<?> anchor) {
        URL url = anchor.getResource(anchor.getSimpleName() + ".class");
        if (url == null || !"jar".equals(url.getProtocol())) return null;
        String jar = url.toString().substring(0, url.toString().indexOf("!/") + 2);
        try (InputStream in = new URL(jar + "META-INF/MANIFEST.MF").openStream()) {
            return new Manifest(in);
        } catch (IOException e) {
            return null;
        }
    }
}
