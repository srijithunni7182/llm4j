package io.github.llm4j.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * CI-03: a module that has tests must be built and tested by both pipelines, so a new module (or an old one nobody wired up, as Loom once was)
 * cannot silently skip CI. Both pipelines build from the root, so a module is covered when the root pom lists it (directly, in the
 * {@code extended} profile, or through an aggregator it lists); a module outside the root build must be named by a pipeline step of its own.
 */
class CiCoversEveryModuleTest {

    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    /** Not expected in the test pipelines: modules being removed. (The example applications is built and tested with -Pextended.) */
    private static final Set<String> EXEMPT_PREFIXES = Set.of();

    @Test
    void everyModuleWithTestsIsTestedByGitHubActionsAndJenkins() throws IOException {
        String workflow = Files.readString(ROOT.resolve(".github/workflows/build-and-deploy.yml"));
        String jenkins = Files.readString(ROOT.resolve("Jenkinsfile"));
        List<String> missing = new ArrayList<>();
        Set<String> reactor = reactorModules();
        for (String module : modulesWithTests()) {
            if (reactor.contains(module)) continue; // built by the root build below
            if (!coveredBy(workflow, "working-directory: ", module)) missing.add(module + " is not in the root build and not tested by .github/workflows/build-and-deploy.yml");
            if (!coveredBy(jenkins, "dir('", module)) missing.add(module + " is not in the root build and not tested by the Jenkinsfile");
        }
        assertTrue(missing.isEmpty(), "add these to the root pom or to CI: " + missing);
    }

    @Test
    void bothPipelinesBuildTheRootDefaultAndTheExtendedProfile() throws IOException {
        for (String file : new String[] {".github/workflows/build-and-deploy.yml", "Jenkinsfile"}) {
            String pipeline = Files.readString(ROOT.resolve(file));
            assertTrue(pipeline.contains("mvn -B verify"), file + " runs the default build from the root");
            assertTrue(pipeline.contains("-Pextended verify"), file + " runs the extended profile from the root");
            assertTrue(pipeline.contains("-Prelease"), file + " checks or performs a release build");
        }
    }

    /** Every module the root pom builds: its {@code <modules>}, the ones of its {@code extended} profile, and what the aggregators among them list. */
    private static Set<String> reactorModules() throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        pending.add("");
        while (!pending.isEmpty()) {
            String dir = pending.poll();
            Path pom = ROOT.resolve(dir).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            var m = java.util.regex.Pattern.compile("<module>([^<]+)</module>").matcher(Files.readString(pom));
            while (m.find()) {
                String child = (dir.isEmpty() ? "" : dir + "/") + m.group(1).trim();
                if (out.add(child)) pending.add(child);
            }
        }
        return out;
    }

    @Test
    void theModulesThatMatterMostAreFound() throws IOException {
        List<String> modules = modulesWithTests();
        for (String m : new String[] {"src/ai-agent4j", "src/ai-agent4j-addons", "src/ai-agent4j-tools", "src/eval4j", "src/eval4j-report", "src/loom/ai-agent4j-loom", "src/loom/ctk", "src/engram/engram-core"}) {
            assertTrue(modules.contains(m), m + " should be discovered; found " + modules);
        }
    }

    /** A module is covered when CI works in its directory or in a directory above it (engram builds all its modules from {@code engram}). */
    private static boolean coveredBy(String pipeline, String marker, String module) {
        String path = module;
        while (true) {
            if (pipeline.contains(marker + path + "'") || pipeline.contains(marker + path + "\n") || pipeline.contains(marker + path + " ")
                    || pipeline.contains(marker + path + "\r")) {
                return true;
            }
            int slash = path.lastIndexOf('/');
            if (slash < 0) return false;
            path = path.substring(0, slash);
        }
    }

    private static List<String> modulesWithTests() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(ROOT, 5)) {
            walk.filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .map(Path::getParent)
                    .filter(dir -> hasTests(dir))
                    .map(dir -> ROOT.relativize(dir).toString().replace('\\', '/'))
                    .filter(rel -> !rel.isEmpty())
                    .filter(rel -> EXEMPT_PREFIXES.stream().noneMatch(rel::startsWith))
                    .filter(rel -> !rel.contains("/target/") && !rel.contains("node_modules"))
                    .forEach(found::add);
        }
        java.util.Collections.sort(found);
        return found;
    }

    private static boolean hasTests(Path module) {
        Path tests = module.resolve("src/test/java");
        if (!Files.isDirectory(tests)) return false;
        try (Stream<Path> files = Files.walk(tests)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith("Test.java"));
        } catch (IOException e) {
            return false;
        }
    }
}
