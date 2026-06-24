package com.visualizer.interpreter.engine;

import com.visualizer.api.dto.ExecutionTrace;
import com.visualizer.interpreter.runtime.CallStack;
import com.visualizer.interpreter.runtime.StackFrame;
import com.visualizer.interpreter.runtime.Value;

/**
 * The final runtime state after {@link ProgramInterpreter#run} completes: the call
 * stack (with the {@code main} frame still on top for inspection), the captured
 * console output, and the recorded {@link ExecutionTrace} of every step.
 */
public record InterpretationResult(CallStack callStack, RuntimeConsole console, ExecutionTrace trace) {

    /** The {@code main} frame left on top of the stack after execution. */
    public StackFrame mainFrame() {
        return callStack.current();
    }

    /** Convenience for tests: read a variable's final value from the main frame. */
    public Value variable(String name) {
        return mainFrame().read(name);
    }

    /** The full accumulated console output. */
    public String output() {
        return console.output();
    }
}
