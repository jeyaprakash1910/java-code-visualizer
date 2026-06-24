package com.visualizer.api.dto;

import java.util.List;

/**
 * A snapshot of a single call-stack frame at a given step.
 */
public record StackFrameDto(
        String frameId,
        String methodName,
        String className,
        boolean isCurrent,
        List<VariableDto> variables
) {}
