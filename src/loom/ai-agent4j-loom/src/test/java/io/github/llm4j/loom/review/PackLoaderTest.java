package io.github.llm4j.loom.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.loom.review.pack.Pack;
import io.github.llm4j.loom.review.pack.PackException;
import io.github.llm4j.loom.review.pack.PackLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R63, R65, R66 of spec 09: packs load only when sound, from their own folder, with every problem
 * listed.
 */
class PackLoaderTest {

    @TempDir Path dir;

    static final String RULE =
            """
            id: ACME-001
            lens: reliability
            severity: low
            about: step
            when: { field: kind, is: delegate }
            title: "{label} is a delegate"
            fixHint: "Think about it."
            examples:
              fires: [{ inline: "agent A { model: \\"m\\"  system: \\"s\\" }\\nworkflow Main(q) { delegate \\"{q}\\" to A -> r  note \\"{r}\\" }" }]
              quiet: [{ inline: "agent A { model: \\"m\\"  system: \\"s\\" }\\nworkflow Main(q) { note \\"hi\\" }" }]
            """;

    static final String MANIFEST =
            "name: acme-policy\nversion: 2.0.1\nfactModel: 1\nrulesSchema: 1\nprefix: ACME\n";

    Path pack(String manifest, String... rules) throws IOException {
        Files.writeString(dir.resolve("pack.yaml"), manifest);
        Path r = dir.resolve("rules");
        Files.createDirectories(r);
        for (int i = 0; i < rules.length; i++)
            Files.writeString(r.resolve("r" + i + ".yaml"), rules[i]);
        return dir;
    }

    @Test
    void aSoundThirdPartyPackLoadsFromAFolderAndItsRulesCarryTheLabel() throws Exception {
        Pack p = PackLoader.directory(pack(MANIFEST, RULE));
        assertThat(p.label()).isEqualTo("acme-policy@2.0.1");
        assertThat(p.rules()).hasSize(1);
        assertThat(p.rules().get(0).pack()).isEqualTo("acme-policy@2.0.1");
        assertThat(p.manifest().core()).isFalse();
    }

    @Test
    void aRuleFileMayBeJsonAndMayHoldAList() throws Exception {
        pack(MANIFEST);
        Files.writeString(
                dir.resolve("rules/one.json"),
                "{\"id\":\"ACME-002\",\"lens\":\"reliability\",\"severity\":\"low\",\"about\":\"step\",\"when\":{\"field\":\"kind\",\"is\":\"loop\"},\"title\":\"t\",\"fixHint\":\"h\",\"examples\":{\"fires\":[{\"inline\":\"agent A { model: \\\"m\\\"  system: \\\"s\\\" }\\nworkflow Main(q) { loop until (x == \\\"y\\\") { delegate \\\"{q}\\\" to A -> x } }\"}],\"quiet\":[{\"inline\":\"agent A { model: \\\"m\\\"  system: \\\"s\\\" }\\nworkflow Main(q) { note \\\"hi\\\" }\"}]}}");
        assertThat(PackLoader.directory(dir).rules())
                .extracting(r -> r.id())
                .containsExactly("ACME-002");
    }

    @Test
    void aMissingManifestOrBadManifestListsEveryProblem() throws Exception {
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("there is no pack.yaml");
        pack("name: Bad Name\nversion: 1\nfactModel: 9\nrulesSchema: 2\nbogus: 1\n", RULE);
        PackException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        PackException.class, () -> PackLoader.directory(dir));
        assertThat(e.problems())
                .anyMatch(s -> s.contains("name is lower case"))
                .anyMatch(s -> s.contains("version is major.minor.patch"))
                .anyMatch(s -> s.contains("factModel 9 is not supported"))
                .anyMatch(s -> s.contains("rulesSchema must be 1"))
                .anyMatch(s -> s.contains("unknown key 'bogus'"))
                .anyMatch(s -> s.contains("needs a prefix"));
    }

    @Test
    void aThirdPartyPackMustUseItsOwnPrefixAndNeverACoreLookingOne() throws Exception {
        pack("name: acme\nversion: 1.0.0\nfactModel: 1\nrulesSchema: 1\nprefix: LR\n", RULE);
        assertThatThrownBy(() -> PackLoader.directory(dir)).hasMessageContaining("needs a prefix");
        pack(MANIFEST, RULE.replace("ACME-001", "LR99"));
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining("ids of pack acme-policy look like ACME-001");
    }

    @Test
    void duplicateIdsWithinAPackAreRefused() throws Exception {
        pack(MANIFEST, RULE, RULE);
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining("ACME-001: the id is used twice");
    }

    @Test
    void aRuleThatDoesNotFitTheFactModelRefusesTheWholePackWithTheFileNamed() throws Exception {
        pack(MANIFEST, RULE.replace("kind, is: delegate", "knid, is: delegate"));
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("rules/r0.yaml")
                .hasMessageContaining("did you mean 'kind'");
    }

    @Test
    void aRuleWithoutExamplesRefusesThePack() throws Exception {
        pack(MANIFEST, RULE.substring(0, RULE.indexOf("examples:")));
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining("at least one script it fires on")
                .hasMessageContaining("stays quiet on");
    }

    @Test
    void aPackWithNoRulesIsRefused() throws Exception {
        pack(MANIFEST);
        assertThatThrownBy(() -> PackLoader.directory(dir)).hasMessageContaining("no rule files");
    }

    @Test
    void theCorePackMayNotUseTheIdsOfTheJavaAdapters() throws Exception {
        pack(
                "name: core\nversion: 1.0.0\nfactModel: 1\nrulesSchema: 1\n",
                RULE.replace("ACME-001", "LA99"));
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining(
                        "LA and LV ids belong to the Java audit and validator adapters");
        pack(
                "name: core\nversion: 1.0.0\nfactModel: 1\nrulesSchema: 1\n",
                RULE.replace("ACME-001", "ACME-001"));
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining("a core rule id is L");
    }

    @Test
    void aPackCannotReadOutsideItsFolderByAPathOrASymbolicLink() throws Exception {
        Path outside = Files.createTempFile("secret", ".loom");
        Files.writeString(outside, "agent A { model: \"m\" system: \"s\" }");
        pack(
                "name: acme-policy\nversion: 2.0.1\nfactModel: 1\nrulesSchema: 1\nprefix: ACME\nrules: [\"../outside.yaml\"]\n");
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .hasMessageContaining("the file is missing or outside the pack");
        Path link = dir.resolve("examples-link.loom");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // the platform has no symbolic links, so there is nothing to escape through
        }
        Pack ok = PackLoader.directory(pack(MANIFEST, RULE));
        assertThat(ok.files().read("examples-link.loom")).isEmpty();
        assertThat(ok.files().read("../" + outside.getFileName())).isEmpty();
        assertThat(ok.files().read("/etc/passwd")).isEmpty();
    }

    @Test
    void invalidYamlIsReportedNotThrownAsAParserTrace() throws Exception {
        pack(MANIFEST, "id: [unclosed\n");
        assertThatThrownBy(() -> PackLoader.directory(dir))
                .isInstanceOf(PackException.class)
                .hasMessageContaining("is not valid YAML");
    }
}
