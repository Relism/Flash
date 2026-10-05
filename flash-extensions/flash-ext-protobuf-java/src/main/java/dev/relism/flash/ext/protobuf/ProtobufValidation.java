package dev.relism.flash.ext.protobuf;

import dev.relism.flash.ext.validation.Validation;

/**
 * The validation engine for protobuf messages. {@link Protobuf} asks for this and not for
 * {@link Validation}, so an engine for protobuf and one for JSON or XML live in one application:
 * each codec family asks for its own.
 */
public interface ProtobufValidation extends Validation {}
