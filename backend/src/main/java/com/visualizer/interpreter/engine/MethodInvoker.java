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

    Value invokeMethod(String methodName, List<Value> arguments);
}
