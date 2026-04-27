package com.jbeans.debug.plugin.client;

public class JbeansDebugException extends RuntimeException {
    private final String errorCode;
    public JbeansDebugException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JbeansDebugException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
