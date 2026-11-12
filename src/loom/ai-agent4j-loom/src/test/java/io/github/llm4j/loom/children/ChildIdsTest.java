package io.github.llm4j.loom.children;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Phase 33b: the child's run id is worked out from the parent's run and the step (D4). */
class ChildIdsTest {

    private static String derive(String parent, String step) throws Exception {
        Class<?> ids = Class.forName("io.github.llm4j.loom.execution.ChildIds");
        Method m = ids.getDeclaredMethod("derive", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, parent, step);
    }

    @Test
    void theSameStepOfTheSameRunGivesTheSameId() throws Exception {
        assertThat(derive("run-1", "Main/s2")).isEqualTo(derive("run-1", "Main/s2"));
    }

    @Test
    void aRewindsNewGenerationGivesAFreshChildAndAnotherRunGivesAnother() throws Exception {
        assertThat(derive("run-1", "Main/s2~2")).isNotEqualTo(derive("run-1", "Main/s2"));
        assertThat(derive("run-2", "Main/s2")).isNotEqualTo(derive("run-1", "Main/s2"));
    }

    @Test
    void stepsThatLookAlikeGiveDifferentIds() throws Exception {
        assertThat(derive("r", "Main/s1.0")).isNotEqualTo(derive("r", "Main/s10"));
        assertThat(derive("r", "Main>Sub/s1")).isNotEqualTo(derive("r", "Main/Sub/s1"));
        assertThat(derive("r", "A_B/s1")).isNotEqualTo(derive("r", "A/B/s1"));
    }

    @Test
    void anIdIsAlwaysAValidRunIdEvenForADeepOrLongStep() throws Exception {
        String deep = "Main>" + "Inner/s3.r2.0".repeat(20);
        String id = derive("a-run", deep);

        assertThat(id).matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,99}");
        assertThat(derive("a-run", deep)).isEqualTo(id);
        assertThat(derive("a-run", deep + "x")).as("two long ids that begin alike differ").isNotEqualTo(id);
    }

    @Test
    void aLetterOutsideAsciiIsEscapedSoTheIdStaysASafeFolderName() throws Exception {
        String id = derive("r", "Main/caf\u00e9");

        assertThat(id).matches("[A-Za-z0-9._@-]+").doesNotContain("\u00e9").isNotEqualTo(derive("r", "Main/cafe"));
    }
}
