package dev.relism.models;

import dev.relism.http.ContentType;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.nio.charset.StandardCharsets;

@Getter
@ToString
public class Response {
    @Setter private int    statusCode;
    private byte[] body;
    private         byte[] contentType;

    public Response(int statusCode, byte[] body, ContentType contentType) {
        this.statusCode  = statusCode;
        this.body        = body;
        this.contentType = contentType.getBytes();
    }

    public Response(int statusCode, String text, ContentType contentType) {
        this.statusCode  = statusCode;
        this.body        = text.getBytes(StandardCharsets.UTF_8);
        this.contentType = contentType.getBytes();
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType.getBytes();
    }

    public void setContentType(String contentType) {
        this.contentType = contentType.getBytes(StandardCharsets.UTF_8);
    }

    public Response setBody(Object body) {
        if (body instanceof byte[] bytes) {
            this.body = bytes;
        } else if (body != null) {
            this.body = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        return this;
    }
}
