package com.visualizer.interpreter.engine;

/**
 * Per-run safety budget. Each executed statement {@link #tick() ticks} the
 * counter; once it passes {@link #maxExecutionSteps()} the run is aborted with
 * {@link ExecutionLimitExceededException}. This bounds infinite loops
 * (including nested ones, since every loop body statement ticks) without the
 * server hanging.
 */
public final class ExecutionContext {

    /** Default budget — generous for teaching programs, small enough to fail fast. */
    public static final int DEFAULT_MAX_EXECUTION_STEPS = 10_000;

    private final int maxExecutionSteps;
    private int currentExecutionSteps;

    public ExecutionContext() {
        this(DEFAULT_MAX_EXECUTION_STEPS);
    }

    public ExecutionContext(int maxExecutionSteps) {
        this.maxExecutionSteps = maxExecutionSteps;
    }

    /** Count one executed statement; abort if the budget is exhausted. */
    public void tick() {
        if (++currentExecutionSteps > maxExecutionSteps) {
            throw new ExecutionLimitExceededException();
        }
    }

    public int maxExecutionSteps() {
        return maxExecutionSteps;
    }

    public int currentExecutionSteps() {
        return currentExecutionSteps;
    }
}
