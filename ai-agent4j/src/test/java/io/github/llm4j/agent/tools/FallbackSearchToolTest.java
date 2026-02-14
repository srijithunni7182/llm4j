package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FallbackSearchToolTest {

    private Tool tool1;
    private Tool tool2;
    private FallbackSearchTool fallbackTool;

    @BeforeEach
    void setUp() {
        tool1 = mock(Tool.class);
        tool2 = mock(Tool.class);
        fallbackTool = new FallbackSearchTool("CustomSearch", List.of(tool1, tool2));
    }

    @Test
    void testSuccessOnFirstTool() throws Exception {
        when(tool1.execute(any())).thenReturn("Result 1");

        String result = fallbackTool.execute(Map.of("query", "test"));

        assertThat(result).isEqualTo("Result 1");
        verify(tool1).execute(any());
        verify(tool2, never()).execute(any());
    }

    @Test
    void testFallbackToSecondTool() throws Exception {
        when(tool1.execute(any())).thenReturn("Error: Out of quota");
        when(tool2.execute(any())).thenReturn("Result 2");

        String result = fallbackTool.execute(Map.of("query", "test"));

        assertThat(result).isEqualTo("Result 2");
        verify(tool1).execute(any());
        verify(tool2).execute(any());
    }

    @Test
    void testAllToolsFail() throws Exception {
        when(tool1.execute(any())).thenReturn("Error 1");
        when(tool2.execute(any())).thenReturn("Error 2");

        String result = fallbackTool.execute(Map.of("query", "test"));

        assertThat(result).contains("All search tools failed");
        assertThat(result).contains("Error 1");
        assertThat(result).contains("Error 2");
    }

    @Test
    void testNameAndDescription() {
        when(tool1.getDescription()).thenReturn("First description");
        assertThat(fallbackTool.getName()).isEqualTo("CustomSearch");
        assertThat(fallbackTool.getDescription()).isEqualTo("First description");
    }
}
