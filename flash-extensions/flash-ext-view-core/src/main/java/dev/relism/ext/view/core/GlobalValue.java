package dev.relism.ext.view.core;

import dev.relism.models.Request;

import java.util.function.Function;

public record GlobalValue(String key, Function<Request, Object> resolver) {}
