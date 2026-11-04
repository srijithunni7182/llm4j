package io.github.llm4j.loom.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.loom.ast.PolicyDef;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyVersionResolutionTest {

    @TempDir Path dir;

    PolicyCatalog catalog() throws Exception {
        Path policies = Files.createDirectories(dir.resolve("policies"));
        Files.writeString(policies.resolve("flat.md"), "## one_rule\nBreaking it looks like this.\n");
        Path legal = Files.createDirectories(policies.resolve("legal"));
        Files.writeString(legal.resolve("v1.md"), "## old_rule\nOld wording.\n");
        Files.writeString(legal.resolve("v2.md"), "## new_rule\nNew wording.\n");
        Files.writeString(policies.resolve("empty.md"), "# nothing here\n");
        return PolicyCatalog.beside(dir);
    }

    static PolicyDef def(String ref) {
        return new PolicyDef("P", ref, null, 1);
    }

    @Test
    void aFlatFileIsResolved() throws Exception {
        var r = catalog().resolve(def("flat")).orElseThrow();
        assertThat(r.rules()).extracting(PolicyFile.Rule::id).containsExactly("one_rule");
    }

    @Test
    void theLatestVersionIsUsedUnlessOneIsPinned() throws Exception {
        PolicyCatalog c = catalog();
        assertThat(c.resolve(def("legal")).orElseThrow().rules().get(0).id()).isEqualTo("new_rule");
        assertThat(c.resolve(def("legal@v1")).orElseThrow().rules().get(0).id()).isEqualTo("old_rule");
        assertThat(c.resolve(def("legal@v1")).orElseThrow().version()).isEqualTo("v1");
    }

    @Test
    void theHashChangesWithTheWordingOnly() throws Exception {
        PolicyCatalog c = catalog();
        String v1 = c.resolve(def("legal@v1")).orElseThrow().hash();
        String v2 = c.resolve(def("legal@v2")).orElseThrow().hash();
        assertThat(v1).isNotEqualTo(v2);
        assertThat(c.resolve(def("legal@v1")).orElseThrow().hash()).isEqualTo(v1);
    }

    @Test
    void aMissingOrEmptyFileHasAReason() throws Exception {
        PolicyCatalog c = catalog();
        assertThat(c.problemWith(def("nosuch"))).contains("nosuch");
        assertThat(c.problemWith(def("empty"))).contains("no rule");
        assertThat(c.problemWith(def("legal@v9"))).contains("v9");
        assertThat(c.problemWith(def("flat"))).isNull();
        assertThat(c.resolve(def("empty"))).isEmpty();
    }
}
