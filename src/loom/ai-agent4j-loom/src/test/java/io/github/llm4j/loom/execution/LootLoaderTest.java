package io.github.llm4j.loom.execution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class LootLoaderTest {

    @Test
    public void testLootFileLoading() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        LootLoader loader = new LootLoader();

        loader.loadIntoRegistry("src/test/resources/mytools.loot", registry);

        assertNotNull(registry.getTool("EmptyTool"));
        assertEquals(
                "MockOutput:test",
                registry.getTool("EmptyTool").execute(java.util.Map.of("test", "test")));
    }
}
