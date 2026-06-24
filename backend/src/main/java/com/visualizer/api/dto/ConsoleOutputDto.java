package com.visualizer.api.dto;

/**
 * One console output entry, cumulative up to the owning step.
 */
public record ConsoleOutputDto(
        int sequence,
        String text,
        boolean newline
) {}
