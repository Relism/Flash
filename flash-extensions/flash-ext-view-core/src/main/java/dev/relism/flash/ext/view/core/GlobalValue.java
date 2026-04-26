package dev.relism.flash.ext.view.core;

import dev.relism.flash.models.Request;

import java.util.function.Function;

public record GlobalValue(String key, Function<Request, Object> resolver) {}
