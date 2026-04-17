package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LoomLoaderTest {

    @Test
    public void testRecursiveLoad() throws IOException {
        LoomLoader loader = new LoomLoader();
        Path path = Paths.get("src/test/resources/imports/parent.loom");
        LoomScript script = loader.load(path.toString());

        assertNotNull(script);
        
        // Check agents from both files
        assertTrue(script.getAgents().stream().anyMatch(a -> a.getName().equals("ParentAgent")));
        assertTrue(script.getAgents().stream().anyMatch(a -> a.getName().equals("ChildAgent")));

        // Check workflows from both files
        assertTrue(script.getWorkflows().stream().anyMatch(w -> w.getName().equals("ParentWorkflow")));
        assertTrue(script.getWorkflows().stream().anyMatch(w -> w.getName().equals("ChildWorkflow")));
    }

    @Test
    public void testCircularDependency() {
        LoomLoader loader = new LoomLoader();
        Path path = Paths.get("src/test/resources/imports/circular_a.loom");
        
        Exception exception = assertThrows(RuntimeException.class, () -> {
            loader.load(path.toString());
        });

        assertTrue(exception.getMessage().contains("Circular dependency"));
    }
}
