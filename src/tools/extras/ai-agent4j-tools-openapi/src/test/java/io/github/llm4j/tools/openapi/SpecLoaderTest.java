package io.github.llm4j.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading a description (phase 36a, R2, G70): what comes out of it, and what it refuses. */
class SpecLoaderTest {

    @TempDir Path dir;

    private ApiDescription load(String json) throws IOException {
        Files.writeString(dir.resolve("api.json"), json);
        return SpecLoader.load("api.json", dir, SpecLoader.Fetcher.NONE);
    }

    private static final String HEAD = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"2\",\"description\":\"About\"},";

    @Test
    void readsTitleVersionServersAndOperations() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"servers\":[{\"url\":\"https://{env}.example.org/v1\",\"variables\":{\"env\":{\"default\":\"api\"}}}],"
                                + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\",\"summary\":\"A\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.title()).isEqualTo("T");
        assertThat(api.description()).isEqualTo("About");
        assertThat(api.version()).isEqualTo("2");
        assertThat(api.servers()).containsExactly("https://api.example.org/v1");
        assertThat(api.operation("a")).isPresent();
        assertThat(api.operation("nope")).isEmpty();
    }

    @Test
    void anOperationWithoutAnIdGetsOneFromItsMethodAndPathAndDuplicatesAreMadeUnique() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"paths\":{\"/x/{id}\":{\"get\":{\"responses\":{\"200\":{\"description\":\"ok\"}}}},"
                                + "\"/y\":{\"get\":{\"operationId\":\"dup\",\"responses\":{\"200\":{\"description\":\"ok\"}}},"
                                + "\"post\":{\"operationId\":\"dup\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.operations()).extracting(ApiOperation::id).contains("get_x_id_", "dup", "dup_2");
    }

    @Test
    void parameterNamesProvidersWouldRefuseGetASafeArgumentName() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\",\"parameters\":["
                                + "{\"name\":\"filter[status]\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}},"
                                + "{\"name\":\"filter_status_\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}},"
                                + "{\"name\":\"body\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}],"
                                + "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        var arguments = api.operation("a").orElseThrow().parameters().stream().map(ApiOperation.Parameter::argument).toList();
        assertThat(arguments).doesNotHaveDuplicates().allMatch(a -> a.matches("[A-Za-z0-9_.-]+")).doesNotContain("body");
    }

    @Test
    void aPathLevelParameterAppliesToEveryOperationOfThePath() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"paths\":{\"/a/{id}\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
                                + "\"get\":{\"operationId\":\"a\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.operation("a").orElseThrow().parameters()).extracting(ApiOperation.Parameter::name).containsExactly("id");
    }

    @Test
    void aFormBodyIsSupportedAndAnXmlOnlyRequiredBodyMakesTheOperationUnavailable() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"paths\":{\"/form\":{\"post\":{\"operationId\":\"form\",\"requestBody\":{\"content\":{\"application/x-www-form-urlencoded\":{\"schema\":{\"type\":\"object\"}}}},\"responses\":{\"200\":{\"description\":\"ok\"}}}},"
                                + "\"/xml\":{\"post\":{\"operationId\":\"xml\",\"requestBody\":{\"required\":true,\"content\":{\"application/xml\":{\"schema\":{\"type\":\"object\"}}}},\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.operation("form").orElseThrow().body().contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(api.operation("xml")).isEmpty();
    }

    @Test
    void aPathThatClimbsIsNotAnOperation() throws Exception {
        ApiDescription api =
                load(HEAD + "\"paths\":{\"/a/../b\":{\"get\":{\"operationId\":\"up\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.operation("up")).isEmpty();
    }

    @Test
    void localReferencesAreResolvedByTheParserInsideTheDocument() throws Exception {
        ApiDescription api =
                load(
                        HEAD
                                + "\"components\":{\"schemas\":{\"Id\":{\"type\":\"integer\"}}},"
                                + "\"paths\":{\"/a/{id}\":{\"get\":{\"operationId\":\"a\",\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"$ref\":\"#/components/schemas/Id\"}}],\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}");

        assertThat(api.operation("a").orElseThrow().parameters().get(0).schema().path("type").asText()).isEqualTo("integer");
    }

    @Test
    void aDocumentThatIsNotADescriptionIsRefusedWithTheReason() throws IOException {
        assertThatThrownBy(() -> load("[1,2,3]")).hasMessageContaining("not an OpenAPI description");
        assertThatThrownBy(() -> load("{\"swagger\":\"2.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{}}"))
                .hasMessageContaining("OpenAPI");
        assertThatThrownBy(() -> SpecLoader.load("missing.json", dir, SpecLoader.Fetcher.NONE))
                .hasMessageContaining("not a file");
    }

    @Test
    void aReferenceToAMissingPlaceInAReferencedDocumentIsRefusedByName() throws Exception {
        Files.writeString(dir.resolve("other.json"), "{\"a\":{}}");

        assertThatThrownBy(
                        () ->
                                load(
                                        HEAD
                                                + "\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\",\"responses\":{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"other.json#/b\"}}}}}}}}}"))
                .hasMessageContaining("points at nothing");
    }

    @Test
    void aRemoteDescriptionIsRefusedWhenFetchingIsNotAllowed() {
        assertThatThrownBy(() -> SpecLoader.load("https://example.org/api.json", dir, SpecLoader.Fetcher.NONE))
                .hasMessageContaining("fetching it is not allowed");
    }

    @Test
    void aRemoteDescriptionsFileReferenceIsRefused() {
        SpecLoader.Fetcher fetcher =
                url ->
                        ("{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{\"/a\":{\"get\":{\"operationId\":\"a\","
                                        + "\"responses\":{\"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"file:///etc/passwd\"}}}}}}}}}")
                                .getBytes();

        assertThatThrownBy(() -> SpecLoader.load("https://example.org/api.json", dir, fetcher))
                .hasMessageContaining("refused");
    }

    @Test
    void aDocumentOverTheLimitIsRefused() {
        SpecLoader.Fetcher huge = url -> new byte[(int) (SpecLoader.MAX_DOCUMENT_BYTES + 1)];

        assertThatThrownBy(() -> SpecLoader.load("https://example.org/api.json", dir, huge))
                .hasMessageContaining("larger than the limit");
    }
}
