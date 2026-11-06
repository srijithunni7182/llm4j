package io.github.llm4j.loom.ast;

/**
 * A file given to an {@code extract} step as one of its inputs: {@code image "{receipt}"} alone, or
 * labelled beside text inputs, {@code (photo = image "{receipt}")}.
 *
 * @param label the input's name in the request, or empty when the file is the only input
 * @param item the kind and the file as written
 */
public record ExtractMedia(String label, AttachItem item) {}
