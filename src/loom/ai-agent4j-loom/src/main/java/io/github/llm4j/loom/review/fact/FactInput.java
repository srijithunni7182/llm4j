package io.github.llm4j.loom.review.fact;

import io.github.llm4j.loom.review.engine.ReviewInput;
import io.github.llm4j.loom.review.settings.ReviewSettings;

/** What a fact provider is given: everything the review read, and the project's settings. */
public record FactInput(ReviewInput review, ReviewSettings settings) {}
