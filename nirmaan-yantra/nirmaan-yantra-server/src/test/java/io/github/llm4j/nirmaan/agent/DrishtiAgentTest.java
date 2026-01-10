package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrishtiAgentTest {

    private final DrishtiAgent drishtiAgent = new DrishtiAgent();

    @Test
    void testIdentity() {
        assertEquals("Drishti", drishtiAgent.getName());
        assertEquals("QA Engineer", drishtiAgent.getRole());
    }

    @Test
    void testReportGeneration_Success() {
        String report = drishtiAgent.generateQAReport(true, "All tests passed.");
        assertNotNull(report);
        assertTrue(report.contains("QA Report"));
        assertTrue(report.contains("**Status**: PASSED"));
        assertTrue(report.contains("All tests passed."));
    }

    @Test
    void testReportGeneration_Failure() {
        String report = drishtiAgent.generateQAReport(false, "Syntax error in line 5.");
        assertNotNull(report);
        assertTrue(report.contains("QA Report"));
        assertTrue(report.contains("**Status**: FAILED"));
        assertTrue(report.contains("Syntax error"));
    }
}
