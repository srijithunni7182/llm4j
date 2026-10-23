package io.github.llm4j.loom.wtest;

/** Something a tool would have done to the world, recorded instead of done. */
record Effect(String tool, String kind, String target, String digest) {}
