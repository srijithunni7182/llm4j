package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The run exporter is used by the {@code weave eval} command-line jar, which carries no JUnit. It
 * must therefore load and run when {@code org.opentest4j} is absent; only stopping a test (the
 * judge budget) needs that class, and only when it happens.
 */
class RunsWithoutJUnitTest {

    /** Defines eval4j's own classes itself and hides the JUnit assumption classes. */
    private static final class WithoutJUnit extends ClassLoader {
        WithoutJUnit() {
            super(RunsWithoutJUnitTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.opentest4j")) throw new ClassNotFoundException(name);
            if (!name.startsWith("io.github.llm4j.eval.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (InputStream in =
                            getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (in == null) throw new ClassNotFoundException(name);
                        byte[] bytes = in.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    @Test
    void theExporterLoadsAndRunsWhereJUnitsAssumptionClassIsNotOnTheClassPath() throws Exception {
        Class<?> run = Class.forName("io.github.llm4j.eval.export.EvalRun", true, new WithoutJUnit());

        Method get = run.getMethod("get");
        assertThat(get.invoke(null)).isNotNull();
        assertThat(run.getClassLoader()).isInstanceOf(WithoutJUnit.class);
    }

    @Test
    void stoppingATestThrowsJUnitsAbortWhereJUnitIsPresent() {
        assertThat(StopTest.because("budget spent"))
                .isInstanceOf(org.opentest4j.TestAbortedException.class)
                .hasMessage("budget spent");
    }

    @Test
    void stoppingATestWhereJUnitIsAbsentThrowsAnOrdinaryExceptionWithTheSameMessage()
            throws Exception {
        Class<?> stop =
                Class.forName("io.github.llm4j.eval.export.StopTest", true, new WithoutJUnit());
        Method because = stop.getDeclaredMethod("because", String.class);
        because.setAccessible(true);

        assertThat((RuntimeException) because.invoke(null, "budget spent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("budget spent");
    }
}
