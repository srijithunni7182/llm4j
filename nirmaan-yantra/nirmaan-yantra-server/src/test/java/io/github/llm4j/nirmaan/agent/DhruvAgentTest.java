package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class DhruvAgentTest {

    private final DhruvAgent dhruvAgent = new DhruvAgent();

    @Test
    void testExtractCommand_Simple() throws Exception {
        String spec = "Build Command: mvn package";
        String cmd = invokeExtractCommand(spec, "Build Command");
        assertEquals("mvn package", cmd);
    }

    @Test
    void testExtractCommand_BoldKey() throws Exception {
        String spec = "**Build Command**: npm install";
        String cmd = invokeExtractCommand(spec, "Build Command");
        assertEquals("npm install", cmd);
    }

    @Test
    void testExtractCommand_WithBackticks() throws Exception {
        String spec = "Test Command: `pytest`";
        String cmd = invokeExtractCommand(spec, "Test Command");
        assertEquals("pytest", cmd);
    }

    @Test
    void testExtractCommand_WithQuotes() throws Exception {
        String spec = "Test Command: 'go test ./...'";
        String cmd = invokeExtractCommand(spec, "Test Command");
        assertEquals("go test ./...", cmd);
    }

    @Test
    void testExtractCommand_MultilineNoise() throws Exception {
        String spec = """
                # Technical Spec

                1. Stack: Python

                2. **Build Command**: `pip install -r requirements.txt`

                3. **Test Command**:
                   `pytest`
                """;

        assertEquals("pip install -r requirements.txt", invokeExtractCommand(spec, "Build Command"));
        // Current logic expects single line. Multi-line extraction is not yet supported
        // by line-scanner,
        // but let's verify what happens if it's on the line after.
        // The line scanner looks for line containing key. If key and value are on
        // separate lines, it might fail or need update.
        // Let's test the "Single Line" assumption which is standard for Rishi.
    }

    @Test
    void testExtractCommand_NotFound() throws Exception {
        String spec = "Nothing here";
        assertNull(invokeExtractCommand(spec, "Build Command"));
    }

    // Reflection helper to access private method
    private String invokeExtractCommand(String text, String key) throws Exception {
        Method method = DhruvAgent.class.getDeclaredMethod("extractCommand", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(dhruvAgent, text, key);
    }
}
