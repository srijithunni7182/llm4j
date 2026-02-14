package io.github.llm4j.agent.tools;

import io.github.llm4j.agent.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CachedSearchToolTest {

    private Tool delegate;
    private CachedSearchTool cachedTool;

    @BeforeEach
    void setUp() {
        delegate = mock(Tool.class);
        cachedTool = new CachedSearchTool(delegate);
        CachedSearchTool.clearCache();
    }

    @Test
    void testCachingBehavior() throws Exception {
        when(delegate.execute(any())).thenReturn("Result from API");

        // First call - should hit the delegate
        String result1 = cachedTool.execute(Map.of("query", "test query"));
        assertThat(result1).isEqualTo("Result from API");
        verify(delegate, times(1)).execute(any());

        // Second call - should return from cache
        String result2 = cachedTool.execute(Map.of("query", "test query"));
        assertThat(result2).contains("Result from API");
        assertThat(result2).contains("retrieved from cache");
        verify(delegate, times(1)).execute(any()); // Still only 1 call to delegate
    }

    @Test
    void testCaseInsensitiveCaching() throws Exception {
        when(delegate.execute(any())).thenReturn("Result");

        cachedTool.execute(Map.of("query", "HELLO"));
        cachedTool.execute(Map.of("query", "hello"));

        verify(delegate, times(1)).execute(any());
    }

    @Test
    void testDoNotCacheErrors() throws Exception {
        when(delegate.execute(any())).thenReturn("Error: API limit reached");

        cachedTool.execute(Map.of("query", "test"));
        cachedTool.execute(Map.of("query", "test"));

        // Since it's an error, it shouldn't be cached, so delegate should be called
        // twice
        verify(delegate, times(2)).execute(any());
    }

    @Test
    void testClearCache() throws Exception {
        when(delegate.execute(any())).thenReturn("Result");

        cachedTool.execute(Map.of("query", "test"));
        CachedSearchTool.clearCache();
        cachedTool.execute(Map.of("query", "test"));

        verify(delegate, times(2)).execute(any());
    }
}
