package dev.relism.flash.ext.mcp;

/** Internal signal carrying a JSON-RPC error code, caught by {@link McpDispatcher} to build the error response. */
final class McpProtocolException extends RuntimeException {

    final int code;

    private McpProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    static McpProtocolException invalidRequest(String message) {
        return new McpProtocolException(JsonRpcErrorCode.INVALID_REQUEST, message);
    }

    static McpProtocolException methodNotFound(String method) {
        return new McpProtocolException(JsonRpcErrorCode.METHOD_NOT_FOUND, "Method not found: " + method);
    }

    static McpProtocolException invalidParams(String message) {
        return new McpProtocolException(JsonRpcErrorCode.INVALID_PARAMS, message);
    }
}
