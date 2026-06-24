package com.visualizer.interpreter.trace;

/**
 * The kinds of execution step the recorder emits. The {@code name()} of each
 * constant is written verbatim into {@link com.visualizer.api.dto.ExecutionStepDto#event()},
 * which is a free-form String in the contract — so adding values here does not
 * change the DTO and the frontend (which does not branch on the value) keeps
 * working unchanged.
 */
public enum StepEvent {
    DECLARE,
    ASSIGN,
    PRINT,
    IF_BRANCH,
    WHILE_START,
    WHILE_END,
    FOR_START,
    FOR_END,
    METHOD_ENTER,
    METHOD_EXIT,
    RETURN,
    ARRAY_CREATE,
    ARRAY_ASSIGN,
    ARRAY_READ,
    GC_MARK,
    GC_COLLECT,
    CONSTRUCTOR_ENTER,
    CONSTRUCTOR_EXIT
}
