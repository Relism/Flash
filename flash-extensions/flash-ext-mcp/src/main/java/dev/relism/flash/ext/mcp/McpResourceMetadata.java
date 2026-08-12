package dev.relism.flash.ext.mcp;

import java.util.List;

/** RFC 9728 OAuth 2.0 Protected Resource Metadata document, built once at boot. */
final class McpResourceMetadata {

    private McpResourceMetadata() {}

    /** {@code scopesSupported} is optional per RFC 9728 — omitted from the document if empty. */
    static String build(String resourceIdentifier, String authorizationServerIssuer, List<String> scopesSupported) {
        return McpJson.buildString(gen -> {
            gen.writeStartObject();
            gen.writeStringField("resource", resourceIdentifier);
            gen.writeArrayFieldStart("authorization_servers");
            gen.writeString(authorizationServerIssuer);
            gen.writeEndArray();
            if (!scopesSupported.isEmpty()) {
                gen.writeArrayFieldStart("scopes_supported");
                for (String scope : scopesSupported) gen.writeString(scope);
                gen.writeEndArray();
            }
            gen.writeEndObject();
        });
    }
}
