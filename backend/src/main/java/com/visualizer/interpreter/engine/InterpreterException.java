package com.visualizer.interpreter.engine;

/**
 * Raised when execution hits a Java construct the engine cannot evaluate (e.g. an
 * arithmetic operator applied to a boolean). Distinct from
 * {@code RuntimeModelException} (storage errors) and {@code UnsupportedFeatureException}
 * (parse-time rejection). In a correctly validated program this should never fire.
 */
public class InterpreterException extends RuntimeException {
    public InterpreterException(String message) {
        super(message);
    }
}
