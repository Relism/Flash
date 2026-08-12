package dev.relism.flash.ext.mcp;

/** Standard JSON-RPC 2.0 error codes used by the MCP transport. */
final class JsonRpcErrorCode {

    private JsonRpcErrorCode() {}

    static final int PARSE_ERROR      = -32700;
    static final int INVALID_REQUEST  = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS   = -32602;
    static final int INTERNAL_ERROR   = -32603;
}
