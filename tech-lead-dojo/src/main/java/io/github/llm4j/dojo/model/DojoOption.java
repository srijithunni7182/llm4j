package io.github.llm4j.dojo.model;

import java.util.Map;

/**
 * A choice presented to the user.
 */
public record DojoOption(
                String id,
                String description,
                Map<String, Integer> hiddenImpact // e.g. "Quality" -> -10. HIDDEN from user until post-mortem.
) {
}
