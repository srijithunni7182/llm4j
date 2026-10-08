package io.github.llm4j.privacy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PIIDetectionResultTest {

    @Test
    void testContainsPII_true() {
        PIIEntity entity =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        PIIDetectionResult result = PIIDetectionResult.builder().addEntity(entity).build();

        assertTrue(result.containsPII());
    }

    @Test
    void testContainsPII_false() {
        PIIDetectionResult result = PIIDetectionResult.builder().build();

        assertFalse(result.containsPII());
    }

    @Test
    void testEntities_emptyList() {
        PIIDetectionResult result = PIIDetectionResult.empty();

        assertFalse(result.containsPII());
        assertEquals(0, result.getEntities().size());
    }

    @Test
    void testEntities_multipleTypes() {
        PIIEntity email =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        PIIEntity phone =
                PIIEntity.builder()
                        .type(PIIType.PHONE)
                        .value("555-1234")
                        .startIndex(20)
                        .endIndex(28)
                        .build();

        PIIDetectionResult result =
                PIIDetectionResult.builder().addEntity(email).addEntity(phone).build();

        assertEquals(2, result.getEntities().size());
        assertTrue(result.containsPII());
    }

    @Test
    void testEntities_preservesOrder() {
        PIIEntity entity1 =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("first@example.com")
                        .startIndex(0)
                        .endIndex(10)
                        .build();

        PIIEntity entity2 =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("second@example.com")
                        .startIndex(20)
                        .endIndex(30)
                        .build();

        PIIDetectionResult result =
                PIIDetectionResult.builder().addEntity(entity1).addEntity(entity2).build();

        assertEquals(entity1, result.getEntities().get(0));
        assertEquals(entity2, result.getEntities().get(1));
    }
}
