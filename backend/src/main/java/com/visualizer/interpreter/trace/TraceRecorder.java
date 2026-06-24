package com.visualizer.interpreter.trace;

import com.visualizer.api.dto.ConsoleOutputDto;
import com.visualizer.api.dto.ExecutionStepDto;
import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.api.dto.HeapObjectDto;
import com.visualizer.api.dto.StackFrameDto;
import com.visualizer.api.dto.TraceMetadataDto;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates {@link ExecutionStepDto}s in execution order, assigning a
 * monotonically increasing {@code stepIndex}, and builds the final
 * {@link ExecutionTrace}.
 *
 * <p>Deliberately independent of execution logic: it receives already-built,
 * immutable snapshot lists and knows nothing about the runtime model or the AST.
 * That keeps it reusable and easy to test in isolation.</p>
 */
public final class TraceRecorder {

    private final List<ExecutionStepDto> steps = new ArrayList<>();

    /** Append one step; its {@code stepIndex} is the current step count. */
    public void record(int line,
                       StepEvent event,
                       String description,
                       List<StackFrameDto> callStack,
                       List<HeapObjectDto> heap,
                       List<ConsoleOutputDto> console) {
        steps.add(new ExecutionStepDto(
                steps.size(),
                line,
                description,
                event.name(),
                callStack,
                heap,
                console
        ));
    }

    public int stepCount() {
        return steps.size();
    }

    /** Build the OK trace for the given entry point (e.g. {@code "Main.main"}). */
    public ExecutionTrace build(String entryPoint) {
        return ExecutionTrace.ok(
                new TraceMetadataDto(steps.size(), entryPoint),
                List.copyOf(steps)
        );
    }
}
