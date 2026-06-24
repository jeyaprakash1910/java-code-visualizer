package com.visualizer.interpreter.engine;

import com.visualizer.interpreter.runtime.Value;

import java.util.List;

/**
 * Invokes a user-defined static method given already-evaluated argument values,
 * returning its return value ({@code null} for {@code void}).
 *
 * <p>Exists to break the cycle between {@link ExpressionEvaluator} (which needs to
 * call methods while evaluating a call expression) and {@link StatementExecutor}
 * (which owns frame push/pop, body execution, and trace emission, and therefore
 * implements this interface). The evaluator depends on the interface, not the
 * executor, and is wired after construction.</p>
 */
public interface MethodInvoker {

    /** Invoke an unqualified static method (resolved within the main class). */
    Value invokeStatic(String methodName, List<Value> arguments);

    /**
     * Invoke {@code receiver.methodName(args)} (Phase 3A): the method declared by
     * {@code className} (the receiver's runtime class), with {@code receiverId} bound
     * as the frame's receiver so the body can read/write the object's fields.
     */
    Value invokeInstance(int receiverId, String className, String methodName, List<Value> arguments);

    /**
     * Run {@code className}'s constructor on a freshly-allocated receiver (Phase 3C).
     * A class without a declared constructor is a no-op (the object keeps its default
     * field values). The receiver id is bound so the body's {@code this.x = ...} works.
     */
    void invokeConstructor(int receiverId, String className, List<Value> arguments);
}
