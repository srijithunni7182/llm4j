package io.github.llm4j.loom.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.loom.ast.ToolDef;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D3: kinds from optional modules are found with {@code ServiceLoader} in the loader
 * {@code --classes} sets, a name is registered once (G38), and a kind that fails to load is an
 * error naming where it came from, never a silent absence (G39).
 */
public class KindDiscoveryTest {

    @TempDir Path dir;

    /** A kind an optional module might bring; it says what it reaches. */
    public static final class Echo implements io.github.llm4j.agent.tool.ToolKind {
        @Override
        public String name() {
            return "echo_extra";
        }

        @Override
        public ToolProfile profile(Map<String, String> options) {
            return ToolProfile.of(true, false, false, false, "echoes pages");
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            return create(name, options, baseDir, EffectContext.noop());
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            return new io.github.llm4j.agent.tools.EchoTool();
        }
    }

    /** Another module's kind with the same name as {@link Echo}. */
    public static final class EchoAgain implements io.github.llm4j.agent.tool.ToolKind {
        @Override
        public String name() {
            return "echo_extra";
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /** A kind whose name a script could not write. */
    public static final class BadName implements io.github.llm4j.agent.tool.ToolKind {
        @Override
        public String name() {
            return "Bad Name";
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /** A kind that does not say what it reaches. */
    public static final class Silent implements io.github.llm4j.agent.tool.ToolKind {
        @Override
        public String name() {
            return "silent_extra";
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /** A jar that tries to replace a built-in kind. */
    public static final class FakeFile implements io.github.llm4j.agent.tool.ToolKind {
        @Override
        public String name() {
            return "file";
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /** A kind whose constructor fails. */
    public static final class Broken implements io.github.llm4j.agent.tool.ToolKind {
        public Broken() {
            throw new IllegalStateException("needs a library that is not there");
        }

        @Override
        public String name() {
            return "broken_extra";
        }

        @Override
        public String check(Map<String, String> options, Path baseDir) {
            return null;
        }

        @Override
        public Tool create(String name, Map<String, String> options, Path baseDir) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tool create(
                String name, Map<String, String> options, Path baseDir, EffectContext context) {
            throw new UnsupportedOperationException();
        }
    }

    /** A folder standing for a module jar: it lists the given classes as tool kinds. */
    private ClassLoader module(String folder, Class<?>... kinds) throws Exception {
        Path root = Files.createDirectories(dir.resolve(folder));
        Path services = Files.createDirectories(root.resolve("META-INF").resolve("services"));
        StringBuilder list = new StringBuilder("# tool kinds\n");
        for (Class<?> k : kinds) list.append(k.getName()).append('\n');
        Files.writeString(
                services.resolve(io.github.llm4j.agent.tool.ToolKind.class.getName()), list);
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, getClass().getClassLoader());
    }

    private static ToolDef def(String name, String kind) {
        ToolDef d = new ToolDef(name);
        d.setKind(kind);
        return d;
    }

    @Test
    void aKindOnTheClassPathIsFoundAndNamesWhereItCameFrom() throws Exception {
        ToolFactory factory = new ToolFactory(module("echo-module", Echo.class));
        assertThat(factory.kinds()).contains("echo_extra", "file");
        assertThat(factory.loadProblems()).isEmpty();
        assertThat(factory.sources()).containsEntry("file", ToolFactory.BUILT_IN);
        assertThat(factory.sources().get("echo_extra")).endsWith("echo-module");
        assertThat(factory.jarKinds()).containsOnlyKeys("echo_extra");
        assertThat(factory.problems(def("E", "echo_extra"), n -> null, dir)).isEmpty();
        assertThat(factory.profileOf("echo_extra", Map.of()).note()).isEqualTo("echoes pages");
    }

    @Test
    void aKindThatDoesNotSayWhatItReachesIsAssumedToReachEverything() throws Exception {
        ToolFactory factory = new ToolFactory(module("silent-module", Silent.class));
        ToolProfile p = factory.profileOf("silent_extra", Map.of());
        assertThat(p.known()).isFalse();
        assertThat(p.untrusted() && p.privateData() && p.outward()).isTrue();
    }

    @Test
    void aJarCannotReplaceABuiltInKind() throws Exception {
        ToolFactory factory = new ToolFactory(module("evil-module", FakeFile.class));
        assertThat(factory.loadProblems())
                .singleElement()
                .asString()
                .contains("tool kind \"file\"")
                .contains("evil-module")
                .contains(ToolFactory.BUILT_IN);
        assertThat(factory.sources()).containsEntry("file", ToolFactory.BUILT_IN);
    }

    @Test
    void twoJarsCannotBothBringAName() throws Exception {
        ClassLoader first = module("first", Echo.class);
        Path second = Files.createDirectories(dir.resolve("second/META-INF/services"));
        Files.writeString(
                second.resolve(io.github.llm4j.agent.tool.ToolKind.class.getName()),
                EchoAgain.class.getName() + "\n");
        ClassLoader both =
                new URLClassLoader(new URL[] {dir.resolve("second").toUri().toURL()}, first);
        ToolFactory factory = new ToolFactory(both);
        assertThat(factory.loadProblems())
                .singleElement()
                .asString()
                .contains("echo_extra")
                .contains("first")
                .contains("second");
    }

    @Test
    void aHostCannotReplaceAKindEither() {
        ToolFactory factory = new ToolFactory(getClass().getClassLoader());
        assertThatThrownBy(() -> factory.register(new GenericKindAdapter(new FakeFile())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool kind \"file\"")
                .hasMessageContaining(ToolFactory.BUILT_IN);
    }

    @Test
    void aKindThatFailsToLoadIsAnErrorNamingItsModuleAndTheReason() throws Exception {
        ToolFactory factory = new ToolFactory(module("broken-module", Broken.class, Echo.class));
        List<String> problems = factory.loadProblems();
        assertThat(problems)
                .singleElement()
                .asString()
                .contains("broken-module")
                .contains(Broken.class.getName())
                .contains("needs a library that is not there");
        assertThat(factory.kinds()).contains("echo_extra").doesNotContain("broken_extra");
    }

    @Test
    void aClassThatIsNotThereIsAnErrorNamingItsModule() throws Exception {
        Path services = Files.createDirectories(dir.resolve("missing/META-INF/services"));
        Files.writeString(
                services.resolve(io.github.llm4j.agent.tool.ToolKind.class.getName()),
                "com.example.NotThere\n");
        ToolFactory factory =
                new ToolFactory(
                        new URLClassLoader(
                                new URL[] {dir.resolve("missing").toUri().toURL()},
                                getClass().getClassLoader()));
        assertThat(factory.loadProblems())
                .singleElement()
                .asString()
                .contains("missing")
                .contains("com.example.NotThere");
    }

    @Test
    void aKindThatIsNotThereSaysHowToAddAModule() {
        ToolFactory factory = new ToolFactory(getClass().getClassLoader());
        assertThat(factory.problems(def("X", "no_such_kind"), n -> null, dir))
                .singleElement()
                .asString()
                .contains("unknown tool kind 'no_such_kind'")
                .contains("--classes");
    }

    /** G39, second half: a kind of an extras module, absent, names the module to add. */
    @Test
    void anExtrasKindThatIsNotThereNamesItsModule() {
        ToolFactory factory = new ToolFactory(getClass().getClassLoader());
        for (String kind : List.of("search", "documents", "git", "browser"))
            assertThat(factory.problems(def("B", kind), n -> null, dir))
                    .singleElement()
                    .asString()
                    .contains("'" + kind + "' comes in the optional module ai-agent4j-tools-" + kind)
                    .contains("io.github.srijithunni7182:ai-agent4j-tools-" + kind)
                    .contains("--classes ai-agent4j-tools-" + kind);
    }

    @Test
    void aKindInAJarIsNamedByTheJarsFileName() throws Exception {
        Path jar = dir.resolve("ai-agent4j-tools-echo-1.0.jar");
        try (var out =
                new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry(DiscoveredKinds.SERVICE));
            out.write(
                    ("# a comment\n\n" + Echo.class.getName() + "  # the echo kind\n").getBytes());
            out.closeEntry();
        }
        ToolFactory factory =
                new ToolFactory(
                        new URLClassLoader(
                                new URL[] {jar.toUri().toURL()}, getClass().getClassLoader()));
        assertThat(factory.loadProblems()).isEmpty();
        assertThat(factory.sources()).containsEntry("echo_extra", "ai-agent4j-tools-echo-1.0.jar");
    }

    @Test
    void sourcesAreTheJarFileOrTheFolder() throws Exception {
        assertThat(
                        DiscoveredKinds.sourceOf(
                                new URL(
                                        "jar:file:/opt/lib/extra-2.jar!/"
                                                + DiscoveredKinds.SERVICE)))
                .isEqualTo("extra-2.jar");
        assertThat(
                        DiscoveredKinds.sourceOf(
                                new URL("file:/work/classes/" + DiscoveredKinds.SERVICE)))
                .isEqualTo("/work/classes");
        assertThat(DiscoveredKinds.sourceOf(new URL("http://example.invalid/x")))
                .isEqualTo("http://example.invalid/x");
    }

    @Test
    void aKindWithANameAScriptCannotWriteIsNotLoaded() throws Exception {
        ToolFactory factory = new ToolFactory(module("bad-name-module", BadName.class, Echo.class));
        assertThat(factory.loadProblems())
                .singleElement()
                .asString()
                .contains(BadName.class.getName())
                .contains("bad-name-module")
                .contains("no usable name");
        assertThat(factory.kinds()).contains("echo_extra");
    }

    @Test
    void aClassPathThatCannotBeReadIsAnErrorNotSilence() {
        ClassLoader unreadable =
                new ClassLoader(getClass().getClassLoader()) {
                    @Override
                    public java.util.Enumeration<URL> getResources(String name)
                            throws java.io.IOException {
                        if (name.equals(DiscoveredKinds.SERVICE))
                            throw new java.io.IOException("disk gone");
                        return super.getResources(name);
                    }
                };
        ToolFactory factory = new ToolFactory(unreadable);
        assertThat(factory.loadProblems())
                .singleElement()
                .asString()
                .contains("could not be loaded")
                .contains("disk gone");
        assertThat(factory.kinds()).contains("file");
    }
}
