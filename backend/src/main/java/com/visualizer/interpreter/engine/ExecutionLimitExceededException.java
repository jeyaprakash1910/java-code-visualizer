package com.visualizer.interpreter.engine;

/**
 * Raised when execution exceeds {@link ExecutionContext#maxExecutionSteps()},
 * the safety guard against infinite loops (e.g. {@code while(true){}} /
 * {@code for(;;){}}). Stops execution cleanly so the server never hangs.
 */
public class ExecutionLimitExceededException extends RuntimeException {

    public static final String MESSAGE =
            "Execution limit exceeded. Possible infinite loop detected.";

    public ExecutionLimitExceededException() {
        super(MESSAGE);
    }
}
