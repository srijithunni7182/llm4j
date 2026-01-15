package io.github.llm4j.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcNotification extends JsonRpcMessage {
    @JsonProperty("method")
    private String method;

    @JsonProperty("params")
    private Object params;

    public JsonRpcNotification() {
    }

    public JsonRpcNotification(String method, Object params) {
        this.method = method;
        this.params = params;
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
}
