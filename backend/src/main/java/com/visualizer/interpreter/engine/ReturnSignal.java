package com.visualizer.interpreter.engine;

import com.visualizer.interpreter.runtime.Value;

/**
 * Control-flow signal used to unwind out of a method body when a {@code return}
 * statement executes (Phase 2D). It is not an error: the method invoker catches
 * it, reads the {@link #value()} (possibly {@code null} for a {@code void}
 * return), and resumes the caller.
 *
 * <p>Modeled as an unchecked throwable so it can cross the recursive
 * statement-execution calls without threading a return slot through every method.
 * No stack trace is captured — it is pure control flow, thrown on every return.</p>
 */
public final class ReturnSignal extends RuntimeException {

    private final transient Value value;

    public ReturnSignal(Value value) {
        super(null, null, false, false);
        this.value = value;
    }

    /** The returned value, or {@code null} for a {@code void} return. */
    public Value value() {
        return value;
    }
}
