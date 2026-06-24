package com.visualizer.api.dto;

import java.util.List;

/**
 * One step in the replayable execution timeline. Holds a full snapshot of
 * stack + heap + console at this point in execution.
 */
public record ExecutionStepDto(
        int stepIndex,
        int line,
        String description,
        String event,                       // DECLARE | ASSIGN | METHOD_ENTER | METHOD_EXIT | NEW_OBJECT | PRINT | GC
        List<StackFrameDto> callStack,
        List<HeapObjectDto> heap,
        List<ConsoleOutputDto> console
) {}
