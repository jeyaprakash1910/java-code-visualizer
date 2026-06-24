package com.visualizer.interpreter.runtime;

/**
 * Base type for problems detected by the runtime model itself (unknown variable,
 * type mismatch, re-declaration, empty stack). Distinct from
 * {@code UnsupportedFeatureException}, which is about parse-time constructs.
 */
public class RuntimeModelException extends RuntimeException {
    public RuntimeModelException(String message) {
        super(message);
    }
}
