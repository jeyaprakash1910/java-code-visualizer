package com.visualizer.api.dto;

/**
 * Request body for POST /api/simulate.
 *
 * @param sourceCode the raw Java source pasted by the user
 * @param options    optional simulation knobs (may be null)
 */
public record SimulateRequest(String sourceCode, Options options) {

    public record Options(Integer maxSteps) {}
}
