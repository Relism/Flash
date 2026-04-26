package dev.relism.flash.ext.openapi;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OpenApiResponseContribution {
    private final String description;
    private final Map<String, Map<String, Object>> headers;

    private OpenApiResponseContribution(String description, Map<String, Map<String, Object>> headers) {
        this.description = description;
        this.headers = Map.copyOf(headers);
    }

    public String description() {
        return description;
    }

    public Map<String, Map<String, Object>> headers() {
        return headers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static OpenApiResponseContribution of(String description) {
        return builder().description(description).build();
    }

    public static final class Builder {
        private String description;
        private final Map<String, Map<String, Object>> headers = new LinkedHashMap<>();

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder header(String name, Map<String, Object> headerObject) {
            headers.put(name, Map.copyOf(headerObject));
            return this;
        }

        public OpenApiResponseContribution build() {
            return new OpenApiResponseContribution(description, headers);
        }
    }
}
