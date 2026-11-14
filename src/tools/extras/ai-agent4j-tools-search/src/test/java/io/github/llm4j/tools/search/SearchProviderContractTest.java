package io.github.llm4j.tools.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Every provider on the class path keeps the contract (D15, D18): a name a script can write, one
 * host given as a host (no scheme, path or address), and no two providers with one name.
 */
class SearchProviderContractTest {

    static List<SearchProvider> providers() {
        List<SearchProvider> out = new ArrayList<>();
        ServiceLoader.load(SearchProvider.class).forEach(out::add);
        return out;
    }

    @Test
    void everyProviderHasAWritableNameAndOneHost() {
        assertThat(providers()).isNotEmpty();
        for (SearchProvider p : providers()) {
            assertThat(p.name()).as(p.getClass().getName()).matches("[a-z][a-z0-9_]*");
            assertThat(p.host()).as(p.name()).matches("[a-z0-9.-]+\\.[a-z]+").doesNotMatch("[0-9.]+");
        }
        assertThat(providers().stream().map(SearchProvider::name).distinct()).hasSameSizeAs(providers());
    }

    @Test
    void theModulesOwnListHasNoFake() throws Exception {
        try (var in = SearchKind.class.getResourceAsStream("/META-INF/services/" + SearchProvider.class.getName())) {
            // the test list shadows nothing: the main list is on the class path too
            assertThat(in).isNotNull();
        }
        var main = java.nio.file.Path.of("src/main/resources/META-INF/services/" + SearchProvider.class.getName());
        assertThat(java.nio.file.Files.readString(main)).doesNotContain("Fake");
    }
}
