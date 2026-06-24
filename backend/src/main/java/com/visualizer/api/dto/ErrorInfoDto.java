package com.visualizer.api.dto;

/**
 * Structured error returned when simulation cannot proceed
 * (parse error, unsupported feature, etc.).
 */
public record ErrorInfoDto(
        String type,     // e.g. "UNSUPPORTED_FEATURE", "PARSE_ERROR"
        String message,
        Integer line     // source line, when known
) {}
