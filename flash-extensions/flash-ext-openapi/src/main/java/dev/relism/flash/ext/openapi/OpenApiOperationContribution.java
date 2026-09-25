package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenApiOperationContribution {
    private static final OpenApiOperationContribution EMPTY =
            new OpenApiOperationContribution(List.of(), Map.of(), null, null, null);

    private final List<Map<String, List<String>>> security;
    private final Map<Integer, OpenApiResponseContribution> responses;
    private final OpenApiResponseContribution allResponses;
    private final ContentType consumes;
    private final ContentType produces;

    private OpenApiOperationContribution(List<Map<String, List<String>>> security,
                                         Map<Integer, OpenApiResponseContribution> responses,
                                         OpenApiResponseContribution allResponses,
                                         ContentType consumes,
                                         ContentType produces) {
        this.security = List.copyOf(security);
        this.responses = Map.copyOf(responses);
        this.allResponses = allResponses;
        this.consumes = consumes;
        this.produces = produces;
    }

    public List<Map<String, List<String>>> security() {
        return security;
    }

    public Map<Integer, OpenApiResponseContribution> responses() {
        return responses;
    }

    public OpenApiResponseContribution allResponses() {
        return allResponses;
    }

    /** What the operation reads its body as, when the contributor knows better than an annotation. */
    public ContentType consumes() {
        return consumes;
    }

    /** What the operation answers in, same. */
    public ContentType produces() {
        return produces;
    }

    public boolean isEmpty() {
        return security.isEmpty() && responses.isEmpty() && allResponses == null
                && consumes == null && produces == null;
    }

    public static OpenApiOperationContribution empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Map<String, List<String>>> security = new ArrayList<>();
        private final Map<Integer, OpenApiResponseContribution> responses = new LinkedHashMap<>();
        private OpenApiResponseContribution allResponses;
        private ContentType consumes;
        private ContentType produces;

        public Builder security(String schemeName, List<String> scopes) {
            security.add(Map.of(schemeName, List.copyOf(scopes)));
            return this;
        }

        public Builder response(int statusCode, OpenApiResponseContribution response) {
            responses.put(statusCode, response);
            return this;
        }

        public Builder allResponses(OpenApiResponseContribution response) {
            this.allResponses = response;
            return this;
        }

        public Builder consumes(ContentType contentType) {
            this.consumes = contentType;
            return this;
        }

        public Builder produces(ContentType contentType) {
            this.produces = contentType;
            return this;
        }

        public OpenApiOperationContribution build() {
            return new OpenApiOperationContribution(security, responses, allResponses, consumes, produces);
        }
    }
}
