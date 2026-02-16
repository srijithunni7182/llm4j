package io.github.llm4j.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolAdapterTest {

    @Mock
    private McpClient mockClient;

    private McpToolAdapter adapter;
    private Map<String, Object> toolDef;

    @BeforeEach
    void setUp() {
        toolDef = new HashMap<>();
        toolDef.put("name", "test-tool");
        toolDef.put("description", "A test tool");
        toolDef.put("inputSchema", Collections.emptyMap());

        adapter = new McpToolAdapter(mockClient, toolDef);
    }

    @Test
    void getName_shouldReturnNameFromDef() {
        assertThat(adapter.getName()).isEqualTo("test-tool");
    }

    @Test
    void getDescription_shouldReturnDescriptionFromDef() {
        assertThat(adapter.getDescription()).isEqualTo("A test tool");
    }

    @Test
    void execute_shouldCallClientAndReturnJson() throws Exception {
        Map<String, Object> args = Map.of("arg", "val");
        Map<String, Object> result = Map.of("content", "success");

        when(mockClient.callTool(eq("test-tool"), eq(args))).thenReturn(result);

        String output = adapter.execute(args);

        verify(mockClient).callTool("test-tool", args);
        assertThat(output).contains("\"content\":\"success\"");
    }
}
