package dev.relism.flash.ext.mcp;

/** Plain-text content block ({@code type: "text"} on the wire). */
public record TextContent(String text) implements Content {}
