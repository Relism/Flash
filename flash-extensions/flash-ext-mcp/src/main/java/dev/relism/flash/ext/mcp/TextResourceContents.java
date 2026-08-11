package dev.relism.flash.ext.mcp;

/** Text resource contents returned from {@code resources/read}. */
public record TextResourceContents(String uri, String mimeType, String text) implements ResourceContents {

    public static TextResourceContents of(String uri, String mimeType, String text) {
        return new TextResourceContents(uri, mimeType, text);
    }
}
