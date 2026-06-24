package com.visualizer.parser;

/**
 * A single, line-numbered reason the submitted source cannot be simulated yet.
 *
 * @param line    1-based source line, or {@code null} if the location is unknown
 * @param message human-friendly explanation of what is unsupported
 */
public record ValidationError(Integer line, String message) {

    @Override
    public String toString() {
        return (line != null ? "Line " + line + ": " : "") + message;
    }
}
