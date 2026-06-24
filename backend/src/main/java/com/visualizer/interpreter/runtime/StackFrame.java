package com.visualizer.interpreter.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One activation record on the {@link CallStack}: the variables live for a single
 * method invocation, kept in declaration order.
 *
 * <p>Re-declaring a name already present in this frame is rejected
 * ({@link VariableAlreadyDeclaredException}), so no two variables in the same
 * scope can shadow each other.</p>
 */
public final class StackFrame {

    private final String methodName;
    private final String className;
    private final Map<String, Variable> variables = new LinkedHashMap<>();

    public StackFrame(String className, String methodName) {
        this.className = className;
        this.methodName = methodName;
    }

    public String methodName() {
        return methodName;
    }

    public String className() {
        return className;
    }

    public boolean isDeclared(String name) {
        return variables.containsKey(name);
    }

    /**
     * Declare a new variable in this frame.
     *
     * @throws VariableAlreadyDeclaredException if the name already exists here
     * @throws TypeMismatchException            if the initial value's type differs
     *                                          from {@code declaredType}
     */
    public Variable declare(String name, ValueType declaredType, Value initialValue) {
        if (variables.containsKey(name)) {
            throw new VariableAlreadyDeclaredException(name);
        }
        Variable variable = new Variable(name, declaredType, initialValue);
        variables.put(name, variable);
        return variable;
    }

    /**
     * Look up a declared variable.
     *
     * @throws UndefinedVariableException if no variable with that name exists here
     */
    public Variable lookup(String name) {
        Variable variable = variables.get(name);
        if (variable == null) {
            throw new UndefinedVariableException(name);
        }
        return variable;
    }

    /**
     * Assign to an existing variable, with a type check.
     *
     * @throws UndefinedVariableException if the variable was never declared
     * @throws TypeMismatchException      if the value's type differs from the
     *                                    variable's declared type
     */
    public void assign(String name, Value value) {
        lookup(name).assign(value);
    }

    /** Read a variable's current value. */
    public Value read(String name) {
        return lookup(name).value();
    }

    /** Variables in declaration order; unmodifiable. */
    public List<Variable> variables() {
        return List.copyOf(variables.values());
    }

    /** Read-only view of the name → variable map, in declaration order. */
    public Map<String, Variable> asMap() {
        return Collections.unmodifiableMap(variables);
    }

    @Override
    public String toString() {
        return className + "." + methodName + variables.values();
    }
}
