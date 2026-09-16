package dev.relism.flash.ext.mcp;

import java.util.List;

/** RFC 9728 OAuth 2.0 Protected Resource Metadata. */
final class McpResourceMetadata {

    private McpResourceMetadata() {}

    /** {@code scopesSupported} is optional — omitted when empty. */
    static String build(String resource, List<String> authorizationServers, List<String> scopesSupported) {
        return McpJson.buildString(gen -> {
            gen.writeStartObject();
            gen.writeStringField("resource", resource);
            gen.writeArrayFieldStart("authorization_servers");
            for (String issuer : authorizationServers) gen.writeString(issuer);
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
