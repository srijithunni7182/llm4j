package io.github.llm4j.dojo.model;

import java.util.List;
import java.util.Map;

/**
 * Represents a significant event in the simulation that requires a decision.
 */
public record DojoEvent(
        String id,
        String title,
        String description,
        StakeholderProfile source, // Can be null if it's a system event (e.g. AWS outage)
        List<DojoOption> options) {
}
