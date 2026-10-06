package io.github.llm4j.eval.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V10.10: a node can carry settings, and every caller written before that still compiles and works. */
class WorkflowTraceNodeTest {

    @Test
    void theFiveArgumentConstructorStillWorksAndHasNoAttributes() {
        WorkflowTrace.Node node = new WorkflowTrace.Node("n1", "delegate", "delegate A", "A", null);

        assertThat(node.attrs()).isEmpty();
        assertThat(node).isEqualTo(new WorkflowTrace.Node("n1", "delegate", "delegate A", "A", null, null));
    }

    @Test
    void attributesAreKeptInOrderAndCannotBeChangedAfterwards() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("retry", 3);
        attrs.put("timeoutMs", 90_000);

        WorkflowTrace.Node node = new WorkflowTrace.Node("n1", "delegate", "delegate A", "A", null, attrs);
        attrs.put("later", true);

        assertThat(node.attrs().keySet()).containsExactly("retry", "timeoutMs");
        assertThatThrownBy(() -> node.attrs().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }
}
