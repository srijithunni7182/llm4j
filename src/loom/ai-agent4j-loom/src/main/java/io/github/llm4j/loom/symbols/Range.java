package io.github.llm4j.loom.symbols;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A span in a file: 0-based lines and columns, columns in UTF-16 code units (an editor's own). */
public record Range(int line, int col, int endLine, int endCol) {

    private static final ObjectMapper JSON = new ObjectMapper();

    ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode start = n.putObject("start");
        start.put("line", line).put("character", col);
        ObjectNode end = n.putObject("end");
        end.put("line", endLine).put("character", endCol);
        return n;
    }
}
