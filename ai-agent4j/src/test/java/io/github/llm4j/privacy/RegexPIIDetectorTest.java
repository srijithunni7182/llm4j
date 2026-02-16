package io.github.llm4j.privacy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegexPIIDetectorTest {

    private RegexPIIDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RegexPIIDetector();
    }

    @Test
    void testDetectEmail_simple() {
        PIIDetectionResult result = detector.detect("Contact me at john@example.com");

        assertTrue(result.containsPII());
        assertEquals(1, result.getEntities().size());
        assertEquals(PIIType.EMAIL, result.getEntities().get(0).getType());
        assertEquals("john@example.com", result.getEntities().get(0).getValue());
    }

    @Test
    void testDetectEmail_multiple() {
        PIIDetectionResult result = detector.detect("Emails: alice@test.com and bob@demo.org");

        assertEquals(2, result.getEntities().size());
        assertTrue(result.getEntities().stream().allMatch(e -> e.getType() == PIIType.EMAIL));
    }

    @Test
    void testDetectPhone_US() {
        PIIDetectionResult result = detector.detect("Call me at (555) 123-4567");

        assertTrue(result.containsPII());
        assertTrue(result.getEntities().stream().anyMatch(e -> e.getType() == PIIType.PHONE));
    }

    @Test
    void testDetectSSN() {
        PIIDetectionResult result = detector.detect("SSN: 123-45-6789");

        assertTrue(result.containsPII());
        assertEquals(PIIType.SSN, result.getEntities().get(0).getType());
    }

    @Test
    void testDetectCreditCard_visa() {
        PIIDetectionResult result = detector.detect("Card: 4532015112830366");

        assertTrue(result.containsPII());
        assertEquals(PIIType.CREDIT_CARD, result.getEntities().get(0).getType());
    }

    @Test
    void testDetect_mixedTypes() {
        String text = "Email: test@example.com, Phone: 555-123-4567, SSN: 123-45-6789";
        PIIDetectionResult result = detector.detect(text);

        assertTrue(result.getEntities().size() >= 3);
    }

    @Test
    void testDetect_noPII() {
        PIIDetectionResult result = detector.detect("This is a clean message with no PII");

        assertFalse(result.containsPII());
        assertEquals(0, result.getEntities().size());
    }

    @Test
    void testDetect_emptyString() {
        PIIDetectionResult result = detector.detect("");

        assertFalse(result.containsPII());
    }

    @Test
    void testDetect_nullInput() {
        PIIDetectionResult result = detector.detect(null);

        assertFalse(result.containsPII());
    }

    @Test
    void testMask_emailWithPlaceholder() {
        String text = "Email me at john@example.com";
        String masked = detector.mask(text, MaskingStrategy.PLACEHOLDER);

        assertTrue(masked.contains("[EMAIL]"));
        assertFalse(masked.contains("john@example.com"));
    }

    @Test
    void testMask_emailWithFull() {
        String text = "Email: test@example.com";
        String masked = detector.mask(text, MaskingStrategy.FULL);

        assertFalse(masked.contains("test@example.com"));
        assertTrue(masked.contains("Email: "));
    }

    @Test
    void testMask_emailWithPartial() {
        String text = "Email: john@example.com";
        String masked = detector.mask(text, MaskingStrategy.PARTIAL);

        assertFalse(masked.contains("john@example.com"));
        assertTrue(masked.startsWith("Email: j")); // First char preserved
    }

    @Test
    void testMask_preservesNonPII() {
        String text = "Hello world, how are you?";
        String masked = detector.mask(text, MaskingStrategy.PLACEHOLDER);

        assertEquals(text, masked); // Should be unchanged
    }

    @Test
    void testMask_multiplePIIEntities() {
        String text = "Contact: alice@test.com or bob@demo.org";
        String masked = detector.mask(text, MaskingStrategy.PLACEHOLDER);

        assertTrue(masked.contains("[EMAIL]"));
        assertFalse(masked.contains("alice@test.com"));
        assertFalse(masked.contains("bob@demo.org"));
    }

    @Test
    void testMask_defaultStrategy() {
        String text = "Email: test@example.com";
        String masked = detector.mask(text); // Uses default PLACEHOLDER

        assertTrue(masked.contains("[EMAIL]"));
    }

    @Test
    void testDetectIPAddress() {
        PIIDetectionResult result = detector.detect("Server IP: 192.168.1.1");

        assertTrue(result.containsPII());
        assertTrue(result.getEntities().stream().anyMatch(e -> e.getType() == PIIType.IP_ADDRESS));
    }

    @Test
    void testDetectURL() {
        PIIDetectionResult result = detector.detect("Visit https://example.com/page");

        assertTrue(result.containsPII());
        assertTrue(result.getEntities().stream().anyMatch(e -> e.getType() == PIIType.URL));
    }
}
