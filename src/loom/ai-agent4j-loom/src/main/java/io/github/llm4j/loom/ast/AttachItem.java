package io.github.llm4j.loom.ast;

import io.github.llm4j.model.MediaKind;

/**
 * One thing a step attaches to the task it sends: a kind and the file, written as a string that may
 * name variables ({@code "{path}"}). Where the file may be is decided at run time, under the
 * script's {@code media_root}.
 *
 * @param kind what the script says it is; checked against the bytes when the file is read
 * @param path the path as written, before variables are filled in
 * @param line the line of the script, for messages
 */
public record AttachItem(MediaKind kind, String path, int line) {}
