package io.github.llm4j.evalreport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** V10.9: the trace schema accepts every graph node kind and an optional {@code attrs}, and old traces still validate. */
class TraceSchemaTest {

    private static final List<String> OLD_KINDS =
            List.of("start", "end", "delegate", "task", "alt", "loop", "handoff", "human_prompt", "parallel", "checkpoint");
    private static final List<String> NEW_KINDS =
            List.of("broadcast", "foreach", "rewind", "call", "guardrail", "decide", "observe", "note", "unknown", "statement");

    private static ObjectNode trace(List<String> kinds) {
        ObjectNode t = SchemaContractTest.MAPPER.createObjectNode();
        t.put("traceId", "t_0123456789abcdef");
        t.put("type", "WORKFLOW");
        ObjectNode workflow = t.putObject("workflow");
        workflow.put("name", "W");
        var nodes = workflow.putObject("graph").putArray("nodes");
        for (String kind : kinds) {
            nodes.addObject().put("id", kind).put("kind", kind).put("label", kind);
        }
        workflow.withObject("graph").putArray("edges");
        return t;
    }

    private static List<String> violations(JsonNode trace) throws Exception {
        return SchemaContractTest.schema("trace").validate(trace).stream().map(Object::toString).toList();
    }

    @Test
    void everyKindAnOldTraceUsedStillValidates() throws Exception {
        assertThat(violations(trace(OLD_KINDS))).isEmpty();
    }

    @Test
    void everyNewKindAndTheOlderGenericOneValidates() throws Exception {
        assertThat(violations(trace(NEW_KINDS))).isEmpty();
    }

    @Test
    void aMistypedKindIsStillCaught() throws Exception {
        assertThat(violations(trace(List.of("delegat")))).isNotEmpty();
    }

    @Test
    void aNodeMayCarryAnAttrsObjectButNotSomethingElse() throws Exception {
        ObjectNode t = trace(List.of("delegate"));
        ObjectNode node = (ObjectNode) t.path("workflow").path("graph").path("nodes").get(0);
        node.putObject("attrs").put("retry", 3);
        assertThat(violations(t)).isEmpty();

        node.put("attrs", "not an object");
        assertThat(violations(t)).isNotEmpty();
    }

    @Test
    void aWorkflowOfAThousandStepsIsAccepted() throws Exception {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add("note");
        }
        ObjectNode t = trace(many);
        var nodes = t.path("workflow").path("graph").path("nodes");
        for (int i = 0; i < nodes.size(); i++) {
            ((ObjectNode) nodes.get(i)).put("id", "n" + i);
        }
        assertThat(violations(t)).isEmpty();
    }

    @Test
    void thePublishedSchemaAndTheTestCopyAreIdentical() throws Exception {
        assertThat(Files.readString(Path.of("src/test/resources/schema/trace.schema.json")))
                .isEqualTo(Files.readString(Path.of("spec/schema/trace.schema.json")));
    }
}
