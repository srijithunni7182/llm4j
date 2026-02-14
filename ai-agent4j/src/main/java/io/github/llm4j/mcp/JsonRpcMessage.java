package io.github.llm4j.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, include = JsonTypeInfo.As.PROPERTY, property = "method")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JsonRpcRequest.class, name = "request"),
        @JsonSubTypes.Type(value = JsonRpcNotification.class, name = "notification"),
        @JsonSubTypes.Type(value = JsonRpcResponse.class, name = "response")
})
// Note: Responses don't strictly have "method", they have "id" and ("result" or
// "error").
// Deduction with Jackson can be tricky for JSON-RPC 2.0 because it's slightly
// irregular.
// We might handle deserialization manually or differently.
// For now, let's define the classes and we can use a custom deserializer or
// loose typing if needed.
public abstract class JsonRpcMessage {
    public final String jsonrpc = "2.0";
}
