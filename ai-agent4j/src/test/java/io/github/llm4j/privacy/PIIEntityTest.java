package io.github.llm4j.privacy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PIIEntityTest {

    @Test
    void testBuilder_allFieldsRequired() {
        PIIEntity entity =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        assertEquals(PIIType.EMAIL, entity.getType());
        assertEquals("test@example.com", entity.getValue());
        assertEquals(0, entity.getStartIndex());
        assertEquals(16, entity.getEndIndex());
    }

    @Test
    void testIndexValidation_startLessThanEnd() {
        assertDoesNotThrow(
                () ->
                        PIIEntity.builder()
                                .type(PIIType.EMAIL)
                                .value("test")
                                .startIndex(0)
                                .endIndex(4)
                                .build());
    }

    @Test
    void testIndexValidation_throwsIfInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PIIEntity.builder()
                                .type(PIIType.EMAIL)
                                .value("test")
                                .startIndex(5)
                                .endIndex(2)
                                .build());
    }

    @Test
    void testEquals_sameValues() {
        PIIEntity entity1 =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        PIIEntity entity2 =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        assertEquals(entity1, entity2);
    }

    @Test
    void testHashCode_consistent() {
        PIIEntity entity =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        assertEquals(entity.hashCode(), entity.hashCode());
    }

    @Test
    void testToString_readable() {
        PIIEntity entity =
                PIIEntity.builder()
                        .type(PIIType.EMAIL)
                        .value("test@example.com")
                        .startIndex(0)
                        .endIndex(16)
                        .build();

        String str = entity.toString();
        assertTrue(str.contains("EMAIL"));
        assertTrue(str.contains("test@example.com"));
    }
}
