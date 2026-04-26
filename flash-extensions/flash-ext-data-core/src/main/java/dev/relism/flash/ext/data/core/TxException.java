package dev.relism.flash.ext.data.core;

public class TxException extends RuntimeException {
    public TxException(String message) { super(message); }
    public TxException(Throwable cause) { super(cause); }
}
