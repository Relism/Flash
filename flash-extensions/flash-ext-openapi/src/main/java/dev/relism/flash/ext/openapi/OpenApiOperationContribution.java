package dev.relism.flash.ext.openapi;

import dev.relism.flash.http.ContentType;

import java.lang.reflect.Field;
import java.lang.reflect.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class OpenApiOperationContribution {
    private static final OpenApiOperationContribution EMPTY =
            new OpenApiOperationContribution(List.of(), Map.of(), null, null, null, null, null, null, null, List.of());

    private final List<Map<String, List<String>>> security;
    private final Map<Integer, OpenApiResponseContribution> responses;
    private final OpenApiResponseContribution allResponses;
    private final ContentType consumes;
    private final ContentType produces;
    private final Type requestType;
    private final Type requestParameters;
    private final Function<Field, String> requestNames;
    private final Type responseType;
    private final List<Map<String, Object>> parameters;

    private OpenApiOperationContribution(List<Map<String, List<String>>> security,
                                         Map<Integer, OpenApiResponseContribution> responses,
                                         OpenApiResponseContribution allResponses,
                                         ContentType consumes,
                                         ContentType produces,
                                         Type requestType,
                                         Type requestParameters,
                                         Function<Field, String> requestNames,
                                         Type responseType,
                                         List<Map<String, Object>> parameters) {
        this.security = List.copyOf(security);
        this.responses = Map.copyOf(responses);
        this.allResponses = allResponses;
        this.consumes = consumes;
        this.produces = produces;
        this.requestType = requestType;
        this.requestParameters = requestParameters;
        this.requestNames = requestNames;
        this.responseType = responseType;
        this.parameters = List.copyOf(parameters);
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

    /** The type the operation reads its body as, when a contributor knows it. */
    public Type requestType() {
        return requestType;
    }

    /**
     * The type the operation reads from the request itself rather than its body. Its parameters are
     * said by the contributor that describes it, through {@link OpenApiContributor#parameters}.
     */
    public Type requestParameters() {
        return requestParameters;
    }

    /** The names the reader of {@link #requestParameters} gives fields, null for one it does not read. */
    public Function<Field, String> requestNames() {
        return requestNames;
    }

    /** The type the operation answers with, same. */
    public Type responseType() {
        return responseType;
    }

    /** Parameter objects added to the operation's own, each as the document writes it. */
    public List<Map<String, Object>> parameters() {
        return parameters;
    }

    public boolean isEmpty() {
        return security.isEmpty() && responses.isEmpty() && allResponses == null
                && consumes == null && produces == null
                && requestType == null && requestParameters == null && responseType == null && parameters.isEmpty();
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
        private Type requestType;
        private Type requestParameters;
        private Function<Field, String> requestNames;
        private Type responseType;
        private final List<Map<String, Object>> parameters = new ArrayList<>();

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

        public Builder requestType(Type type) {
            this.requestType = type;
            return this;
        }

        /** An input read from the request, and the names whatever reads it gives fields: a codec's {@code wireName}. */
        public Builder requestParameters(Type type, Function<Field, String> names) {
            this.requestParameters = type;
            this.requestNames = names;
            return this;
        }

        public Builder responseType(Type type) {
            this.responseType = type;
            return this;
        }

        /** One parameter object: {@code name}, {@code in}, {@code schema} and whatever else it says. */
        public Builder parameter(Map<String, Object> parameter) {
            parameters.add(Collections.unmodifiableMap(new LinkedHashMap<>(parameter)));
            return this;
        }

        public OpenApiOperationContribution build() {
            return new OpenApiOperationContribution(security, responses, allResponses,
                    consumes, produces, requestType, requestParameters, requestNames, responseType, parameters);
        }
    }
}
