package io.github.llm4j.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest extends JsonRpcMessage {
    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params; // Can be Map or List

    @JsonProperty("id")
    private Object id; // String or Number

    public JsonRpcRequest() {}

    public JsonRpcRequest(String method, Object params, Object id) {
        this.method = method;
        this.params = params;
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }
}
