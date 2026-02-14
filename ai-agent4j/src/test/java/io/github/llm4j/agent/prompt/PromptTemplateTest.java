package io.github.llm4j.agent.prompt;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateTest {

    @Test
    void testRenderNoVariables() {
        PromptTemplate template = new PromptTemplate("id", "v1", "Hello World");
        assertThat(template.render()).isEqualTo("Hello World");
    }

    @Test
    void testRenderWithVariables() {
        PromptTemplate template = new PromptTemplate("id", "v1", "Hello {{name}}!");
        String result = template.render(Map.of("name", "Claude"));
        assertThat(result).isEqualTo("Hello Claude!");
    }

    @Test
    void testRenderWithMissingVariable() {
        PromptTemplate template = new PromptTemplate("id", "v1", "Hello {{name}}!");
        String result = template.render(Collections.emptyMap());
        // Based on implementation, missing variable is replaced by empty string
        assertThat(result).isEqualTo("Hello !");
    }

    @Test
    void testRenderWithMultipleVariables() {
        PromptTemplate template = new PromptTemplate("id", "v1", "{{greeting}} {{name}}");
        String result = template.render(Map.of(
                "greeting", "Hi",
                "name", "User"));
        assertThat(result).isEqualTo("Hi User");
    }
}
