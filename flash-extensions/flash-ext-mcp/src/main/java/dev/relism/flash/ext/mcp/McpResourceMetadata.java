package dev.relism.flash.ext.mcp;

/** RFC 9728 OAuth 2.0 Protected Resource Metadata document, built once at boot. */
final class McpResourceMetadata {

    private McpResourceMetadata() {}

    static String build(String resourceIdentifier, String authorizationServerIssuer) {
        return McpJson.buildString(gen -> {
            gen.writeStartObject();
            gen.writeStringField("resource", resourceIdentifier);
            gen.writeArrayFieldStart("authorization_servers");
            gen.writeString(authorizationServerIssuer);
            gen.writeEndArray();
            gen.writeEndObject();
        });
    }
}
