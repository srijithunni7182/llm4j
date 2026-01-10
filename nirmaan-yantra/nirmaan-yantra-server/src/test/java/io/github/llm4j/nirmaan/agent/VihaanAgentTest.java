package io.github.llm4j.nirmaan.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VihaanAgentTest {

    private final VihaanAgent vihaanAgent = new VihaanAgent();

    @Test
    void testParseFiles_SingleFile() {
        String input = """
                Here is the code:
                [FILE: src/main.py]
                print("Hello World")
                [EOF]
                """;

        Map<String, String> files = vihaanAgent.parseFiles(input);
        assertEquals(1, files.size());
        assertEquals("print(\"Hello World\")", files.get("src/main.py"));
    }

    @Test
    void testParseFiles_MultipleFiles() {
        String input = """
                [FILE: one.txt]
                First Only
                [EOF]

                [FILE: two.txt]
                Second
                Line
                [EOF]
                """;

        Map<String, String> files = vihaanAgent.parseFiles(input);
        assertEquals(2, files.size());
        assertEquals("First Only", files.get("one.txt"));
        assertEquals("Second\nLine", files.get("two.txt"));
    }

    @Test
    void testParseFiles_FormattingResilience() {
        String input = """
                [FILE:   spaces.txt  ]
                content
                [EOF]
                """;
        Map<String, String> files = vihaanAgent.parseFiles(input);
        assertEquals("content", files.get("spaces.txt"));
    }

    @Test
    void testParseFiles_NoMatches() {
        String input = "Just some chat text without delimiters.";
        Map<String, String> files = vihaanAgent.parseFiles(input);
        assertTrue(files.isEmpty());
    }
}
