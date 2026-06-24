package com.visualizer.api.dto;

import java.util.List;

/**
 * Top-level response for POST /api/simulate. The complete, replayable
 * "film" of execution that the frontend plays back locally.
 */
public record ExecutionTrace(
        String status,                  // "OK" | "ERROR"
        ErrorInfoDto error,             // null when status == OK
        TraceMetadataDto metadata,      // null when status == ERROR
        List<ExecutionStepDto> steps
) {
    public static ExecutionTrace ok(TraceMetadataDto metadata, List<ExecutionStepDto> steps) {
        return new ExecutionTrace("OK", null, metadata, steps);
    }

    public static ExecutionTrace error(ErrorInfoDto error) {
        return new ExecutionTrace("ERROR", error, null, List.of());
    }
}
