package io.github.llm4j.loom.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The tool kinds optional modules bring (phase 35, D3): every {@link
 * io.github.llm4j.agent.tool.ToolKind} a jar lists in {@code META-INF/services}, found with the
 * JDK's {@link ServiceLoader} in a given class loader (the one {@code --classes} sets). Nothing is
 * scanned: only the classes a jar names are loaded. A kind that fails to load is a problem naming
 * the jar or folder that listed it and the reason, never a silent absence.
 *
 * @param kinds what was found, each with where it came from, in the order found
 * @param problems the kinds that could not be loaded
 */
record DiscoveredKinds(List<Found> kinds, List<String> problems) {

    /** Where a module lists the kinds it brings. */
    static final String SERVICE =
            "META-INF/services/" + io.github.llm4j.agent.tool.ToolKind.class.getName();

    /** A kind and the jar or folder whose service file listed it. */
    record Found(ToolKind kind, String source) {}

    DiscoveredKinds {
        kinds = List.copyOf(kinds);
        problems = List.copyOf(problems);
    }

    /** The kinds the modules visible to {@code loader} bring. */
    static DiscoveredKinds in(ClassLoader loader) {
        Map<String, String> listedBy = listedBy(loader);
        List<Found> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Iterator<io.github.llm4j.agent.tool.ToolKind> it =
                ServiceLoader.load(io.github.llm4j.agent.tool.ToolKind.class, loader).iterator();
        // ServiceLoader moves on to the next provider after one fails; the bound only guards
        // against a loader that does not
        int failures = 0;
        while (failures <= listedBy.size()) {
            try {
                if (!it.hasNext()) break;
                io.github.llm4j.agent.tool.ToolKind kind = it.next();
                String source = listedBy.getOrDefault(kind.getClass().getName(), "the class path");
                String name = kind.name();
                if (name == null || !name.matches("[a-z][a-z0-9_]*"))
                    problems.add(
                            "the tool kind "
                                    + kind.getClass().getName()
                                    + " from "
                                    + source
                                    + " has no usable name (lower-case letters, digits and _),"
                                    + " so it was not loaded");
                else found.add(new Found(new GenericKindAdapter(kind), source));
            } catch (ServiceConfigurationError e) {
                failures++;
                problems.add(failure(e, listedBy));
            }
        }
        return new DiscoveredKinds(found, problems);
    }

    /** What went wrong, naming the jar that listed the class when it can be told. */
    private static String failure(ServiceConfigurationError e, Map<String, String> listedBy) {
        String text = e.getMessage() == null ? "" : e.getMessage();
        String source = "a jar on the class path";
        String provider = null;
        for (Map.Entry<String, String> entry : listedBy.entrySet()) {
            if (text.contains(entry.getKey())) {
                provider = entry.getKey();
                source = entry.getValue();
            }
        }
        Throwable cause = e.getCause();
        while (cause != null && cause.getCause() != null) cause = cause.getCause();
        return "a tool kind listed in "
                + source
                + (provider == null ? "" : " (" + provider + ")")
                + " could not be loaded: "
                + (cause == null
                        ? text
                        : cause.getClass().getSimpleName() + ": " + cause.getMessage())
                + "; add what it needs to --classes or remove it";
    }

    /** Each class a service file names, and the jar or folder holding that file. */
    private static Map<String, String> listedBy(ClassLoader loader) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Enumeration<URL> files = loader.getResources(SERVICE);
            while (files.hasMoreElements()) {
                URL file = files.nextElement();
                String source = sourceOf(file);
                for (String name : classNames(file)) out.putIfAbsent(name, source);
            }
        } catch (IOException e) {
            // ServiceLoader reads the same files and reports what it cannot read; only the names of
            // the sources are lost here
            return out;
        }
        return out;
    }

    private static List<String> classNames(URL file) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(file.openStream(), StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                int hash = line.indexOf('#');
                String name = (hash >= 0 ? line.substring(0, hash) : line).strip();
                if (!name.isEmpty()) out.add(name);
            }
        }
        return out;
    }

    /** The jar's file name, or the folder's path, that holds a service file. */
    static String sourceOf(URL serviceFile) {
        String s = serviceFile.toString();
        int bang = s.indexOf("!/");
        if (s.startsWith("jar:") && bang > 0) {
            String jar = s.substring(4, bang);
            return jar.substring(jar.lastIndexOf('/') + 1);
        }
        int at = s.lastIndexOf("/" + SERVICE);
        String folder = at > 0 ? s.substring(0, at) : s;
        return folder.startsWith("file:") ? folder.substring("file:".length()) : folder;
    }
}
