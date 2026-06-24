package com.visualizer.support;

/**
 * Thrown when the submitted code uses a Java construct the interpreter does
 * not yet support. Carries the offending source line for friendly reporting.
 *
 * <p>Not used by the Phase 0 mock, but part of the locked contract.</p>
 */
public class UnsupportedFeatureException extends RuntimeException {

    private final Integer line;

    public UnsupportedFeatureException(String message, Integer line) {
        super(message);
        this.line = line;
    }

    public Integer getLine() {
        return line;
    }
}
