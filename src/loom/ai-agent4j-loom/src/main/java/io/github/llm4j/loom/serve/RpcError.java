package io.github.llm4j.loom.serve;

/** A failure with a code and a kind the client can act on. */
public final class RpcError extends Exception {

    public static final int PARSE = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int UNKNOWN_METHOD = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL = -32603;
    public static final int OUTSIDE_WORKSPACE = -32001;
    public static final int TIMEOUT = -32002;
    public static final int CANCELLED = -32003;
    public static final int ENGINE = -32004;

    private final int code;
    private final String kind;

    public RpcError(int code, String kind, String message) {
        super(message);
        this.code = code;
        this.kind = kind;
    }

    public int code() {
        return code;
    }

    /** A short name such as {@code timeout} or {@code outside-workspace}. */
    public String kind() {
        return kind;
    }
}
