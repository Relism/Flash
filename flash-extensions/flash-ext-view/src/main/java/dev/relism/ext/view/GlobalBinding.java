package dev.relism.ext.view;

import dev.relism.models.Request;

import java.util.function.Function;

record GlobalBinding(String key, Function<Request, Object> resolver) {}
