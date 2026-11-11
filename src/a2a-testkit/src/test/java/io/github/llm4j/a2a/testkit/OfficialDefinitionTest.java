package io.github.llm4j.a2a.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OfficialDefinitionTest {

    @Test
    void theBundledDefinitionIsTheOneFromTheTagUnmodified() {
        assertThat(OfficialDefinition.bundledSha256()).isEqualTo(OfficialDefinition.SHA256);
    }

    @Test
    void aWellFormedTaskIsValid() {
        OfficialDefinition.validate("Task", """
            {"id":"t-1","contextId":"c-1","status":{"state":"TASK_STATE_WORKING"}}""");
    }

    @Test
    void aSendMessageRequestWithTheDocumentedShapeIsValid() {
        OfficialDefinition.validate("SendMessageRequest", """
            {"message":{"messageId":"m-1","role":"ROLE_USER","parts":[{"text":"hi"}]},
             "configuration":{"returnImmediately":true}}""");
    }

    @Test
    void snakeCaseNamesAreRefusedEvenThoughProtobufWouldAcceptThem() {
        assertThat(OfficialDefinition.problems("Task", """
            {"id":"t-1","context_id":"c-1","status":{"state":"TASK_STATE_WORKING"}}""")).isNotEmpty();
    }

    @Test
    void anUnknownFieldIsRefused() {
        assertThat(OfficialDefinition.problems("Task", """
            {"id":"t-1","status":{"state":"TASK_STATE_WORKING"},"kind":"task"}""")).isNotEmpty();
    }

    @Test
    void lowercaseAndNumericStatesAreRefused() {
        assertThat(OfficialDefinition.problems("Task", """
            {"id":"t","status":{"state":"working"}}""")).isNotEmpty();
        assertThat(OfficialDefinition.problems("Task", """
            {"id":"t","status":{"state":2}}""")).isNotEmpty();
    }

    @Test
    void aMissingRequiredFieldIsRefused() {
        assertThat(OfficialDefinition.problems("Task", """
            {"id":"t"}""")).anyMatch(p -> p.contains("status"));
        assertThat(OfficialDefinition.problems("Message", """
            {"messageId":"m","role":"ROLE_USER"}""")).anyMatch(p -> p.contains("parts"));
    }

    @Test
    void aPartWithNoContentOrTwoContentsIsRefused() {
        assertThat(OfficialDefinition.problems("Part", """
            {"filename":"a.txt"}""")).isNotEmpty();
        assertThat(OfficialDefinition.problems("Part", """
            {"text":"a","url":"https://x.example.com/a"}""")).isNotEmpty();
        assertThat(OfficialDefinition.problems("Part", """
            {"data":{"k":[1,2]}}""")).isEmpty();
    }

    @Test
    void anEmptyNextPageTokenMustStillBePresentInAListResponse() {
        assertThat(OfficialDefinition.problems("ListTasksResponse", """
            {"tasks":[],"pageSize":50,"totalSize":0}""")).anyMatch(p -> p.contains("nextPageToken"));
        OfficialDefinition.validate("ListTasksResponse", """
            {"tasks":[],"nextPageToken":"","pageSize":50,"totalSize":0}""");
    }

    @Test
    void validateThrowsWithEveryProblem() {
        assertThatThrownBy(() -> OfficialDefinition.validate("Task", "{}")).isInstanceOf(AssertionError.class);
    }
}
